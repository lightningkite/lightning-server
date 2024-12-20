package com.lightningkite.lightningserver.aws

import com.lightningkite.lightningserver.auth.authAny
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.core.ContentType
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.serverLogger
import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.http.HttpContent
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.serialization.AnonType
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.websocket.WebSocketConnection
import com.lightningkite.lightningserver.websocket.QueryParamWebSocketHandler
import com.lightningkite.lightningserver.websocket.WebSocketClose
import com.lightningkite.lightningserver.websocket.WebSocketConnectRequest
import com.lightningkite.lightningserver.websocket.WebSocketFrame
import com.lightningkite.lightningserver.websocket.WebSocketHandler
import com.lightningkite.lightningserver.websocket.WebSocketTopic
import com.lightningkite.lightningserver.websocket.WebSockets
import com.lightningkite.lightningserver.websocket.didConnectTracked
import com.lightningkite.lightningserver.websocket.disconnectTracked
import com.lightningkite.lightningserver.websocket.messageFromClientTracked
import com.lightningkite.lightningserver.websocket.messageFromSubscriptionTracked
import com.lightningkite.lightningserver.websocket.text
import com.lightningkite.lightningserver.websocket.willConnectTracked
import io.ktor.http.decodeURLPart
import kotlinx.coroutines.future.await
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.http.SdkHttpFullResponse
import software.amazon.awssdk.services.apigatewaymanagementapi.model.GoneException
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import java.util.Base64
import kotlin.time.measureTime

class AwsAdapterWs(val root: AwsAdapter) {
    val dynamo: DynamoDbAsyncClient by lazy { DynamoDbAsyncClient.builder().region(root.region).build() }
    val webSocketDynamo by lazy {
        AwsWebSocketDynamoDb(
            dynamo, generalSettings().wsUrl.substringAfter("://")
                .substringBefore('?')
                .filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' })
    }

    @Serializable
    data class WebSocketDidConnect(
        val socketId: String,
        val connection: WebSocketConnectRequest,
        @Contextual val storage: AnonType
    )

    @Serializable
    data class WebSocketPublish(
        val topic: String,
        @Contextual val data: AnonType,
    )

    suspend inline fun <T, R> withMid(
        path: ServerPath,
        request: WebSocketConnectRequest,
        handler: WebSocketHandler<T>,
        socketId: String,
        stateString: AnonType,
        action: (WsMid<T>) -> R
    ): R {
        val mid: WsMid<T> = WsMid<T>(
            request = request,
            path = path,
            handler = handler,
            socketId = socketId,
            stateString = stateString
        )
        val r = action(mid)
        mid.commit()
        return r
    }

    inner class WsMid<T> constructor(
        override val request: WebSocketConnectRequest,
        val path: ServerPath,
        val handler: WebSocketHandler<T>,
        val socketId: String,
        val stateString: AnonType
    ) : WebSocketConnection<T> {
        override var currentState: T = stateString.value(handler.storageSerializer)

        override suspend fun repullState(): T = webSocketDynamo.statesAlone(listOf(socketId))[socketId]!!
            .let { root.communicationEncoding.decodeBytes(handler.storageSerializer, it) }
            .also { currentState = it }

        val queue = ArrayList<(T) -> T>()
        override suspend fun queueStateUpdate(modification: (T) -> T) {
            queue.add(modification)
        }

        suspend fun commit(): T {
            if (queue.isEmpty()) return currentState
            var newState = currentState
            while (true) {
                val stateString = root.communicationEncoding.encodeBytes(handler.storageSerializer, currentState)
                newState = queue.fold(currentState) { item, apply -> apply(item) }
                val newStateString = root.communicationEncoding.encodeBytes(handler.storageSerializer, newState)
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

        override suspend fun <T> subscribe(topic: WebSocketTopic<T>) {
            webSocketDynamo.subscribe(path.toString(), topic.topic, socketId)
        }

        override suspend fun unsubscribe(topic: String) {
            webSocketDynamo.unsubscribe(topic, socketId)
        }

        override suspend fun send(frame: WebSocketFrame) {
            try {
                val result = root.apiGatewayManagement.postToConnection {
                    it.connectionId(socketId)
                    it.data(SdkBytes.fromUtf8String(frame.text))
                }.await()
                val r = result.sdkHttpResponse()
                if (!r.isSuccessful) {
                    serverLogger.warn("Socket ${socketId} had a send failure.")
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
                serverLogger.warn("Socket ${socketId} is gone, but a send was attempted.")
                webSocketDynamo.clean(socketId)
                false
            }
        }

        override suspend fun close(reason: WebSocketClose) = webSocketClose(socketId, reason)

    }

    suspend fun webSocketClose(socketId: String, reason: WebSocketClose) {
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
        val tr = event.data.retriever
        webSocketDynamo.forSubscribers(event.topic) { path, ids ->
            try {
                val p = ServerPath(path)
                val h = if (p == ServerPath.root) rootWs else WebSockets.handlers[p] ?: run {
                    serverLogger.warn("No handler found for $p")
                    return@forSubscribers
                }
                h as WebSocketHandler<Any?>
                // TODO: could retrieve more states at once?
                val states = webSocketDynamo.states(ids)
                for (socketId in ids) {
                    val s = states[socketId] ?: continue
                    try {
                        withMid(p, s.connectRequest, h, socketId, AnonType(s.state)) { mid ->
                            h.messageFromSubscriptionTracked(p, mid, event.topic, tr)
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
    suspend fun <T> publish(topic: String, serializer: KSerializer<T>, output: T) {
        try {
            root.lambdaClient.invoke {
                it.functionName(System.getenv("AWS_LAMBDA_FUNCTION_NAME"))
                it.qualifier(System.getenv("AWS_LAMBDA_FUNCTION_VERSION"))
                it.invocationType(InvocationType.EVENT)

                it.payload(
                    SdkBytes.fromUtf8String(
                        Serialization.Internal.json.encodeToString(
                            WebSocketPublish.serializer(),
                            WebSocketPublish(
                                topic,
                                AnonType(output, serializer)
                            )
                        )
                    )
                )
            }.await()
        } catch (e: Exception) {
            e.report()
        }
    }

    suspend fun handleWebsocketDidConnect(event: WebSocketDidConnect): APIGatewayV2HTTPResponse {
        val path = ServerPath.root
        try {
            withMid(path, event.connection, rootWs, event.socketId, event.storage) { mid ->
                rootWs.didConnectTracked(
                    path,
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
        val body = event.body?.let { raw ->
            if (event.isBase64Encoded)
                HttpContent.Binary(
                    Base64.getDecoder().decode(raw),
                    headers.contentType ?: ContentType.Application.OctetStream
                )
            else
                HttpContent.Text(raw, headers.contentType ?: ContentType.Text.Plain)
        }
        var queryParams =
            (event.multiValueQueryStringParameters
                ?: mapOf()).entries.flatMap { it.value.map { v -> it.key to v.decodeURLPart() } }

        return when (event.requestContext.routeKey) {
            "\$connect" -> {
                // TODO: Remove this fugly hack and deal with websocket auth better
                queryParams = queryParams.flatMap {
                    if (it.first == "path") listOf(it) + it.second.substringAfter('?').split('&')
                        .map { it.substringBefore('=') to it.substringAfter('=') }
                    else listOf(it)
                }
                val lkEvent = WebSocketConnectRequest(
                    path = ServerPath.root,
                    parts = mapOf(),
                    wildcard = null,
                    queryParameters = queryParams,
                    headers = headers,
                    domain = event.requestContext.domainName,
                    protocol = "https",
                    sourceIp = event.requestContext.identity.sourceIp ?: "0.0.0.0"
                )
                try {
                    lkEvent.authAny()  // force cache
                    val storage = rootWs.willConnectTracked(ServerPath.root, lkEvent)
                    val storageBytes = root.communicationEncoding.encodeBytes(rootWs.storageSerializer, storage)
                    val storageString = root.communicationEncoding.encodeString(rootWs.storageSerializer, storage)
                    lkEvent.authAny()  // Forces auth to be cached
                    webSocketDynamo.setState(event.requestContext.connectionId, lkEvent, storageBytes)
                    try {
                        root.lambdaClient.invoke {
                            it.functionName(System.getenv("AWS_LAMBDA_FUNCTION_NAME"))
                            it.qualifier(System.getenv("AWS_LAMBDA_FUNCTION_VERSION"))
                            it.invocationType(InvocationType.EVENT)

                            it.payload(
                                SdkBytes.fromUtf8String(
                                    Serialization.Internal.json.encodeToString(
                                        WebSocketDidConnect.serializer(),
                                        WebSocketDidConnect(
                                            event.requestContext.connectionId,
                                            lkEvent,
                                            AnonType(storageString)
                                        )
                                    )
                                )
                            )
                        }.await()
                    } catch (e: Exception) {
                        e.report()
                    }
                    root.logger.info("WebSocket ${event.requestContext.connectionId} connected successfully.")
                    APIGatewayV2HTTPResponse(200)
                } catch (e: Exception) {
                    APIGatewayV2HTTPResponse(500, body = e.message ?: "")
                }
            }

            "\$disconnect" -> {
                try {
                    val state =
                        webSocketDynamo.state(event.requestContext.connectionId) ?: return APIGatewayV2HTTPResponse(204)
                    withMid(
                        ServerPath.root,
                        state.connectRequest,
                        rootWs,
                        event.requestContext.connectionId,
                        AnonType(state.state)
                    ) { mid ->
                        rootWs.disconnectTracked(
                            ServerPath.root,
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

            else -> if (body == null || body.length == 0L)
                APIGatewayV2HTTPResponse(200)
            else {
                val state =
                    webSocketDynamo.state(event.requestContext.connectionId) ?: return APIGatewayV2HTTPResponse(204)
                try {
                    withMid(
                        ServerPath.root,
                        state.connectRequest,
                        rootWs,
                        event.requestContext.connectionId,
                        AnonType(state.state),
                    ) { mid ->
                        rootWs.messageFromClientTracked(
                            ServerPath.root,
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