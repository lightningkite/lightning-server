package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.exceptionSettings
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.aws.AwsConnections
import com.lightningkite.services.data.KotlinBytesFormat
import kotlinx.coroutines.future.await
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.http.SdkHttpFullResponse
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import java.net.URLDecoder
import java.util.Base64

internal class AwsAdapterWs(val root: AwsAdapter) {
    val wsUrl: String get() = with(root) { generalSettings.invoke().wsUrl }
    val encoding: KotlinBytesFormat get() = root.internalSerialization.kotlinBytesFormat

    val dynamo: DynamoDbAsyncClient by lazy {
        DynamoDbAsyncClient.builder().region(root.region)
//        .overrideConfiguration(AwsConnections.clientOverrideConfiguration)
            .httpClient(AwsConnections.asyncClient).build()
    }
    val webSocketDynamo by lazy {
        AwsWebSocketDynamoDb(
            dynamo, wsUrl.substringAfter("://")
                .substringBefore('?')
                .filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' },
            encoding
        )
    }

    @Serializable
    data class WebSocketDidConnect(
        val socketId: String,
        val connection: WebSocketConnectRequest<Nothing>,
        val storage: AnonType
    )

    @Serializable
    data class WebSocketPublish(
        val topic: String,
        val data: AnonType,
    )

    private suspend inline fun <P: PathSpec, T, R> withMid(
        path: P,
        request: WebSocketConnectRequest<P>,
        handler: WebSocketHandler<P, T>,
        socketId: String,
        stateString: AnonType,
        action: (WsMid<P, T>) -> R
    ): R {
        val mid: WsMid<P, T> = WsMid<P, T>(
            request = request,
            path = path,
            handler = handler,
            socketId = socketId,
            stateAnonType = stateString
        )
        val r = action(mid)
        mid.commit()
        return r
    }

    private inner class WsMid<P: PathSpec, T> constructor(
        override val request: WebSocketConnectRequest<P>,
        val path: P,
        val handler: WebSocketHandler<P, T>,
        val socketId: String,
        val stateAnonType: AnonType
    ) : WebSocketConnection<P, T>, ServerRuntime by root {
        override var currentState: T = stateAnonType.value(encoding, handler.storageSerializer)

        override suspend fun repullState(): T = webSocketDynamo.statesAlone(listOf(socketId))[socketId]!!
            .let { encoding.decodeFromByteArray(handler.storageSerializer, it) }
            .also { currentState = it }

        val queue = ArrayList<(T) -> T>()
        override suspend fun queueStateUpdate(modification: (T) -> T) {
            queue.add(modification)
        }

        suspend fun commit(): T {
            if (queue.isEmpty()) return currentState
            var newState = currentState
            while (true) {
                val stateString = encoding.encodeToByteArray(handler.storageSerializer, currentState)
                newState = queue.fold(currentState) { item, apply -> apply(item) }
                val newStateString = encoding.encodeToByteArray(handler.storageSerializer, newState)
                if (webSocketDynamo.updateState(socketId, stateString, newStateString)) break
                currentState = repullState()
            }
            queue.clear()
            currentState = newState
            return newState
        }

        override suspend fun updateStateImmediately(modification: (T) -> T): T {
            queue.add(modification)
            return commit()
        }

        override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) {
            webSocketDynamo.subscribe(path.toString(), topic.path.toString(internalSerialization.stringArrayFormat), socketId)
        }

        override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) {
            webSocketDynamo.unsubscribe(topic.path.toString(internalSerialization.stringArrayFormat), socketId)
        }

        override suspend fun send(frame: WebSocketFrame) {
            try {
                val result = root.apiGatewayManagement.postToConnection {
                    it.connectionId(socketId)
                    it.data(SdkBytes.fromUtf8String(frame.text))
                }.await()
                val r = result.sdkHttpResponse()
                if (!r.isSuccessful) {
                    root.logger.warn("Socket ${socketId} had a send failure.")
                    throw Exception(
                        "Failed to send socket message to $socketId ${r.statusCode()} - ${
                            try {
                                r.statusText().get()
                            } catch (e: Exception) {
                                "?"
                            }
                        } - ${(r as? SdkHttpFullResponse)?.content()?.get()?.use { it.reader().readText() }}"
                    )
                }
                true
            } catch (e: GoneException) {
                root.logger.warn("Socket ${socketId} is gone, but a send was attempted.")
                webSocketDynamo.clean(socketId)
                false
            }
        }

        override suspend fun close(reason: WebSocketClose) = webSocketClose(socketId, reason)

    }

    private suspend fun webSocketClose(socketId: String, reason: WebSocketClose) {
        try {
            val result = root.apiGatewayManagement.deleteConnection {
                it.connectionId(socketId)
            }.await()
            val r = result.sdkHttpResponse()
            if (!r.isSuccessful) {
                throw Exception(
                    "Failed to close $socketId: ${r.statusCode()} - ${
                        try {
                            r.statusText().get()
                        } catch (e: Exception) {
                            "?"
                        }
                    } - ${(r as? SdkHttpFullResponse)?.content()?.get()?.use { it.reader().readText() }}"
                )
            }
            true
        } catch (e: GoneException) {
            false
        }
    }


    suspend fun publishHandler(event: WebSocketPublish): APIGatewayV2HTTPResponse {
        @Suppress("UNCHECKED_CAST")
        val fullTopicMatch = root.server.webSocketTopics.match(root.internalSerialization.stringArrayFormat, event.topic) as? PathSpecMap.Match<WebSocketTopic<PathSpec, Any?>> ?: run {
            root.logger.warn("No topic found for ${event.topic}")
            return APIGatewayV2HTTPResponse(500, body = "No topic found for ${event.topic}")
        }
        val fullValue = event.data.value(root.internalSerialization.kotlinBytesFormat, fullTopicMatch.value!!.type)
        webSocketDynamo.forSubscribers(event.topic) { path, ids ->
            try {
                val match = root.server.endpoints.match(root.externalSerialization.stringArrayFormat, path) { it.websocket } ?: run {
                    root.logger.warn("No handler found for $path")
                    return@forSubscribers
                }
                val p = match.path
                val h = match.value
                @Suppress("UNCHECKED_CAST")
                h as WebSocketHandler<PathSpec, Any?>
                // TODO: could retrieve more states at once?
                val states = webSocketDynamo.states(ids)
                for (socketId in ids) {
                    val s = states[socketId] ?: continue
                    try {
                        @Suppress("UNCHECKED_CAST")
                        withMid<PathSpec, Any?, Unit>(p.pathSpec, s.connectRequest as WebSocketConnectRequest<PathSpec>, h, socketId, AnonType(s.state)) { mid ->
                            h.messageFromSubscriptionWithMetrics(p.pathSpec, mid, WebSocketSubscriptionMessage(fullTopicMatch.value!!, fullTopicMatch.path.rawPathArguments, fullValue))
                        }
                    } catch (e: Exception) {
                        // Suppress, already reported inside *Tracked
                        webSocketClose(socketId, WebSocketClose.INTERNAL_ERROR)
                    }
                }
            } catch (e: Exception) {
                root.logger.warn("WebSocket subs fail $path: ${e.message}")
            }
        }

        return APIGatewayV2HTTPResponse(200)
    }

    val rootWs = QueryParamWebSocketHandler()
    val rootPath = PathSpec0(listOf(), PathSpec.Afterwards.None)
    suspend fun <T> publish(topic: String, serializer: KSerializer<T>, output: T) {
        try {
            root.lambdaClient.invoke {
                it.functionName(System.getenv("AWS_LAMBDA_FUNCTION_NAME"))
                it.qualifier(System.getenv("AWS_LAMBDA_FUNCTION_VERSION"))
                it.invocationType(InvocationType.EVENT)

                it.payload(
                    SdkBytes.fromUtf8String(
                        root.internalSerialization.json.encodeToString(
                            WebSocketPublish.serializer(),
                            WebSocketPublish(
                                topic,
                                AnonType(root.internalSerialization.kotlinBytesFormat, output, serializer)
                            )
                        )
                    )
                )
            }.await()
        } catch (e: Exception) {
            with(root) {
                exceptionSettings().report(e, "METRICS")
            }
        }
    }
    suspend fun handleWebsocketDidConnect(event: WebSocketDidConnect): APIGatewayV2HTTPResponse {
        try {
            @Suppress("UNCHECKED_CAST")
            withMid(rootPath, event.connection as WebSocketConnectRequest<PathSpec0>, rootWs, event.socketId, event.storage) { mid ->
                rootWs.didConnectWithMetrics(
                    rootPath,
                    mid
                )
                return APIGatewayV2HTTPResponse(200)
            }
        } catch (e: Exception) {
            webSocketClose(event.socketId, WebSocketClose.INTERNAL_ERROR)
            return APIGatewayV2HTTPResponse(500, body = e.message ?: "")
        }
    }

    suspend fun handleWebsocket(event: APIGatewayV2WebsocketRequest): APIGatewayV2HTTPResponse {
        val headers =
            HttpHeaders(event.multiValueHeaders?.entries?.flatMap { it.value.map { v -> it.key to v } } ?: listOf())
        val body: WebSocketFrame? = event.body?.let { raw ->
            if (event.isBase64Encoded)
                WebSocketFrame.Binary(
                    Base64.getDecoder().decode(raw)
                )
            else
                WebSocketFrame.Text(raw)
        }
        var queryParams =
            (event.multiValueQueryStringParameters
                ?: mapOf()).entries.flatMap { it.value.map { v -> it.key to URLDecoder.decode(v, Charsets.UTF_8) } }

        return when (event.requestContext.routeKey) {
            "\$connect" -> {
                queryParams = queryParams.flatMap {
                    if (it.first == "path") listOf(it) + it.second.substringAfter('?').split('&')
                        .map { it.substringBefore('=') to it.substringAfter('=') }
                    else listOf(it)
                }
                val lkEvent = WebSocketConnectRequest(
                    path = RawPath<PathSpec0>(""),
                    queryParameters = queryParams,
                    headers = headers,
                    domain = event.requestContext.domainName,
                    protocol = "https",
                    sourceIp = event.requestContext.identity.sourceIp ?: "0.0.0.0"
                )
                try {
                    val storage = rootWs.willConnectWithMetrics(rootPath, root, lkEvent)
                    val storageBytes = encoding.encodeToByteArray(rootWs.storageSerializer, storage)
                    webSocketDynamo.setState(event.requestContext.connectionId, lkEvent, storageBytes)
                    try {
                        root.lambdaClient.invoke {
                            it.functionName(System.getenv("AWS_LAMBDA_FUNCTION_NAME"))
                            it.qualifier(System.getenv("AWS_LAMBDA_FUNCTION_VERSION"))
                            it.invocationType(InvocationType.EVENT)

                            @Suppress("UNCHECKED_CAST")
                            it.payload(
                                SdkBytes.fromUtf8String(
                                    root.internalSerialization.json.encodeToString(
                                        WebSocketDidConnect.serializer(),
                                        WebSocketDidConnect(
                                            event.requestContext.connectionId,
                                            lkEvent as WebSocketConnectRequest<Nothing>,
                                            AnonType(storageBytes)
                                        )
                                    )
                                )
                            )
                        }.await()
                    } catch (e: Exception) {
                        with(root) { exceptionSettings().report(e, "AWS WEBSOCKET DIDCONNECT") }
                    }
                    root.logger.info("WebSocket ${event.requestContext.connectionId} connected successfully.")
                    APIGatewayV2HTTPResponse(200)
                } catch (http: HttpStatusException) {
                    APIGatewayV2HTTPResponse(http.status.code, body = http.message)
                } catch (e: Exception) {
                    APIGatewayV2HTTPResponse(500, body = e.message ?: "")
                }
            }

            "\$disconnect" -> {
                try {
                    val state =
                        webSocketDynamo.state(event.requestContext.connectionId) ?: return APIGatewayV2HTTPResponse(204)
                    @Suppress("UNCHECKED_CAST")
                    withMid(
                        rootPath,
                        state.connectRequest as WebSocketConnectRequest<PathSpec0>,
                        rootWs,
                        event.requestContext.connectionId,
                        AnonType(state.state)
                    ) { mid ->
                        rootWs.disconnectWithMetrics(
                            rootPath,
                            mid,
                            WebSocketClose.NORMAL
                        )
                    }
                    APIGatewayV2HTTPResponse(200)
                } catch (e: Exception) {
                    APIGatewayV2HTTPResponse(500, body = e.message ?: "")
                }.also {
                    webSocketDynamo.clean(event.requestContext.connectionId)
                }
            }

            else -> if (body == null || body.isEmpty())
                APIGatewayV2HTTPResponse(200)
            else {
                val state =
                    webSocketDynamo.state(event.requestContext.connectionId) ?: return APIGatewayV2HTTPResponse(204)
                try {
                    @Suppress("UNCHECKED_CAST")
                    withMid(
                        rootPath,
                        state.connectRequest as WebSocketConnectRequest<PathSpec0>,
                        rootWs,
                        event.requestContext.connectionId,
                        AnonType(state.state),
                    ) { mid ->
                        try {
                            if (with(root) { generalSettings() }.debug && state.connectRequest.queryParameter("debug")
                                    ?.toBoolean() == true
                            ) {
                                mid.send(
                                    WebSocketFrame(
                                        "!!! DEBUG AWS INFO !!! - ${
                                            mid.currentState
                                        }"
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            with(root) { exceptionSettings().report(Exception("Failed to send debug info", e), "AWS WEBSOCKET MESSAGE DEBUG") }
                        }
                        rootWs.messageFromClientWithMetrics(
                            rootPath,
                            mid,
                            WebSocketFrame(event.body)
                        )
                        APIGatewayV2HTTPResponse(200)
                    }
                } catch (e: Exception) {
                    webSocketClose(event.requestContext.connectionId, WebSocketClose.INTERNAL_ERROR)
                    APIGatewayV2HTTPResponse(500, body = e.message ?: "")
                }
            }
        }
    }
}