package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.AnonType
import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.serializers.KotlinBytesFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.http.SdkHttpFullResponse
import software.amazon.awssdk.services.apigatewaymanagementapi.model.*
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import java.util.*

/**
 * Thrown when a socket's stored state is no longer in the database, meaning the socket has already
 * disconnected and been cleaned up.  This is a normal race - a disconnect can land while another Lambda
 * invocation is midway through handling a message - and is distinct from a genuine processing failure,
 * so callers discard the work instead of reporting an error and closing an already-dead socket.
 */
internal class WebSocketStateGoneException(socketId: String) :
    Exception("WebSocket $socketId has no stored state; it has already disconnected.")

internal class AwsAdapterWs(val root: AwsAdapter) {
    val wsUrl: String get() = with(root) { generalSettings.invoke().wsUrl }
    val encoding: KotlinBytesFormat get() = root.internalSerialization.kotlinBytesFormat

    val webSocketDynamo by lazy {
        AwsWebSocketDynamoDb(
            root.dynamo, wsUrl.substringAfter("://")
                .substringBefore('?')
                .filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' },
            encoding
        )
    }

    @Serializable
    data class WebSocketDidConnect(
        val socketId: String,
        val connection: WebSocketConnectRequest<Nothing>,
        /**
         * `didConnect` is its own Lambda invocation, so the socket's identity has to travel with it.
         *
         * Null only for a payload fired by a deployment that predates this field.  It needs the explicit
         * default as well as the nullable type: kotlinx.serialization treats a field without a default
         * as required regardless of nullability, so without it the whole payload fails to decode and
         * `didConnect` is lost in the outer failure handler with no response and no trace.
         */
        val initiator: Initiator.WebSocket? = null,
        val storage: AnonType,
    ) : AwsLambdaInput

    @Serializable
    data class WebSocketPublish(
        val topic: String,
        val data: AnonType,
    ) : AwsLambdaInput

    private suspend inline fun <P : PathSpec, T, R> withMid(
        path: P,
        request: WebSocketConnectRequest<P>,
        handler: WebSocketHandler<P, T>,
        socketId: String,
        stateString: AnonType,
        action: (WsMid<P, T>) -> R,
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

    private inner class WsMid<P : PathSpec, T> constructor(
        override val request: WebSocketConnectRequest<P>,
        val path: P,
        val handler: WebSocketHandler<P, T>,
        val socketId: String,
        val stateAnonType: AnonType,
    ) : WebSocketConnection<P, T> {
        override var currentState: T = stateAnonType.value(encoding, handler.storageSerializer)

        /**
         * The exact bytes we believe are stored in the database right now, used as the comparison value
         * for the optimistic lock in [AwsWebSocketDynamoDb.updateState].
         *
         * This must advance on every successful write and every re-read.  A handler commonly commits more
         * than once per invocation - the multiplex handler alone commits once to register a channel and
         * again when that channel subscribes - and if this kept pointing at the state the invocation
         * started with, every commit after the first would deterministically lose its own lock.
         *
         * We keep the raw bytes rather than re-serializing [currentState] because serialization is not
         * guaranteed to be byte-for-byte stable, and the lock compares bytes.
         */
        private var committedStateBytes: ByteArray = stateAnonType.serializedBytes()

        override suspend fun repullState(): T = fetchStateBytes()
            .let { encoding.decodeFromByteArray(handler.storageSerializer, it) }
            .also { currentState = it }

        /** Reads the socket's stored state, failing with [WebSocketStateGoneException] if the socket is no longer tracked. */
        private suspend fun fetchStateBytes(): ByteArray =
            (webSocketDynamo.statesAlone(listOf(socketId))[socketId]
                ?: throw WebSocketStateGoneException(socketId))
                .also { committedStateBytes = it }

        val queue = ArrayList<(T) -> T>()
        override suspend fun queueStateUpdate(modification: (T) -> T) {
            queue.add(modification)
        }

        suspend fun commit(): T {
            if (queue.isEmpty()) return currentState
            var attempts = 0
            val maxAttempts = 50 // Safety limit to prevent infinite loops

            while (true) {
                attempts++
                if (attempts > maxAttempts) {
                    root.logger.error {
                        "Failed to commit WebSocket state for $socketId after $maxAttempts attempts. " +
                                "This indicates either extreme contention or a serialization issue. Queue size: ${queue.size}"
                    }
                    throw IllegalStateException(
                        "Failed to commit WebSocket state for $socketId after $maxAttempts attempts"
                    )
                }

                val newState = queue.fold(currentState) { item, apply -> apply(item) }
                val newStateBytes = encoding.encodeToByteArray(handler.storageSerializer, newState)

                if (webSocketDynamo.updateState(socketId, committedStateBytes, newStateBytes)) {
                    if (attempts > 1) {
                        root.logger.debug { "WebSocket state committed for $socketId after $attempts attempts" }
                    }
                    committedStateBytes = newStateBytes
                    queue.clear()
                    currentState = newState
                    return newState
                }

                // Another invocation (a didConnect, a publish, or another client message) beat us to it.
                // Re-read the winning state and re-apply our queued modifications on top of it.
                root.logger.debug { "WebSocket state update retry $attempts for $socketId" }
                currentState = encoding.decodeFromByteArray(handler.storageSerializer, fetchStateBytes())
            }
        }

        override suspend fun updateStateImmediately(modification: (T) -> T): T {
            queue.add(modification)
            return commit()
        }

        override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) {
            webSocketDynamo.subscribe(path.toString(), with(root) { topic.path() }, socketId)
        }

        override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) {
            webSocketDynamo.unsubscribe(with(root) { topic.path() }, socketId)
        }

        override suspend fun send(frame: WebSocketFrame) {
            try {
                val result = root.apiGatewayWsPostToConnection(PostToConnectionRequest.builder().also {
                    it.connectionId(socketId)
                    it.data(SdkBytes.fromUtf8String(frame.text))
                }.build())
                val r = result.sdkHttpResponse()
                if (!r.isSuccessful) {
                    root.logger.warn { "Socket $socketId had a send failure." }
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
            } catch (e: GoneException) {
                root.logger.warn { "Socket $socketId is gone, but a send was attempted." }
                webSocketDynamo.clean(socketId)
            }
        }

        override suspend fun close(reason: WebSocketClose) {
            root.logger.info { "Closing socket $socketId with reason $reason as requested." }
            webSocketClose(socketId, reason)
        }

    }

    private suspend fun webSocketClose(socketId: String, reason: WebSocketClose) {
        try {
            val result = root.apiGatewayWsDeleteConnection(DeleteConnectionRequest.builder().also {
                it.connectionId(socketId)
            }.build())
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
        } catch (e: GoneException) {
        }
    }


    suspend fun publishHandler(event: WebSocketPublish): APIGatewayV2HTTPResponse {
        @Suppress("UNCHECKED_CAST")
        val fullTopicMatch = root.server.webSocketTopics.match(
            root.internalSerialization.stringArrayFormat,
            event.topic
        ) as? PathSpecMap.Match<WebSocketTopic<PathSpec, Any?>> ?: run {
            root.logger.warn { "No topic found for ${event.topic}" }
            return APIGatewayV2HTTPResponse(500, body = "No topic found for ${event.topic}")
        }
        val fullValue = event.data.value(root.internalSerialization.kotlinBytesFormat, fullTopicMatch.value.type)
        webSocketDynamo.forSubscribers(event.topic) { path, ids ->
            try {
                // AWS Lambda WebSockets all go through root path "/" with query param routing.
                // When the subscription was stored with path "/", use rootWs (QueryParamWebSocketHandler)
                // directly instead of trying to match from endpoints.
                val p: ResolvedPath<PathSpec>
                val h: WebSocketHandler<PathSpec, Any?>
                if (path == "/" || path.isEmpty()) {
                    @Suppress("UNCHECKED_CAST")
                    p = ResolvedPath(rootPath) as ResolvedPath<PathSpec>
                    @Suppress("UNCHECKED_CAST")
                    h = rootWs as WebSocketHandler<PathSpec, Any?>
                } else {
                    val match =
                        root.server.endpoints.match(root.externalSerialization.stringArrayFormat, path) { it.webSocket }
                            ?: run {
                                root.logger.warn { "No handler found for $path" }
                                return@forSubscribers
                            }
                    p = match.path
                    @Suppress("UNCHECKED_CAST")
                    h =
                        root.server.interceptIncomingSocket(match.value) as WebSocketHandler<PathSpec, Any?>
                }
                // TODO: could retrieve more states at once?
                val states = webSocketDynamo.states(ids)
                for (socketId in ids) {
                    // Handled per socket, so one previous-schema row cannot cost the rest of the batch
                    // its push.  Reading these rows as a group used to throw on the first legacy one and
                    // abandon the whole page, healthy sockets included.
                    val s = when (val row = states.getValue(socketId)) {
                        SocketRow.Absent -> continue
                        is SocketRow.Legacy -> {
                            webSocketClose(socketId, WebSocketClose.GOING_AWAY)
                            webSocketDynamo.clean(socketId)
                            continue
                        }

                        is SocketRow.Current -> row
                    }
                    try {
                        @Suppress("UNCHECKED_CAST")
                        withMid<PathSpec, Any?, Unit>(
                            p.pathSpec,
                            s.connectRequest as WebSocketConnectRequest<PathSpec>,
                            h,
                            socketId,
                            AnonType(s.state)
                        ) { mid ->
                            h.messageFromSubscriptionWithMetrics(
                                p.pathSpec,
                                root,
                                with(root) { s.initiator.phase(Initiator.WebSocket.Phase.SubscriptionMessage) },
                                mid,
                                WebSocketSubscriptionMessage(
                                    fullTopicMatch.value,
                                    fullTopicMatch.path.rawPathArguments,
                                    fullValue
                                )
                            )
                        }
                    } catch (e: WebSocketStateGoneException) {
                        root.logger.debug { "Socket $socketId disconnected while delivering topic '${event.topic}'; skipping." }
                    } catch (e: Exception) {
                        // Suppress, already reported inside *Tracked
                        root.logger.error(e) { "Closing socket $socketId because subscription message from topic '${p.pathSpec}' failed to process." }
                        webSocketClose(socketId, WebSocketClose.INTERNAL_ERROR)
                    }
                }
            } catch (e: Exception) {
                root.logger.warn { "WebSocket subs fail $path: ${e.message}" }
            }
        }

        return APIGatewayV2HTTPResponse(200)
    }

    val rootWs = root.server.interceptIncomingSocket(QueryParamWebSocketHandler())
    val rootPath = PathSpec0(listOf(), PathSpec.Afterwards.None)
    suspend fun <T> publish(topic: String, serializer: KSerializer<T>, output: T) {
        try {
            root.invokeLambda(InvokeRequest.builder().also {
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
            }.build())
        } catch (e: Exception) {
            with(root) {
                logger.error(e) { "Publish failed for $topic" }
            }
        }
    }

    /**
     * Sends a frame directly to a specific WebSocket connection, bypassing pub/sub.
     *
     * @param socketId The AWS API Gateway connection ID
     * @param frame The frame to send
     * @return true if sent successfully, false if the connection is gone
     */
    suspend fun sendDirect(socketId: String, frame: WebSocketFrame): Boolean {
        return try {
            val result = root.apiGatewayWsPostToConnection(PostToConnectionRequest.builder().also {
                it.connectionId(socketId)
                it.data(SdkBytes.fromUtf8String(frame.text))
            }.build())
            result.sdkHttpResponse().isSuccessful
        } catch (e: GoneException) {
            root.logger.warn { "Socket $socketId is gone during direct send." }
            webSocketDynamo.clean(socketId)
            false
        }
    }

    suspend fun handleWebSocketDidConnect(event: WebSocketDidConnect): APIGatewayV2HTTPResponse {
        // The `${'$'}connect` that fired this payload ran under a deployment with no initiator, which
        // means it also wrote a row this deployment cannot use.  The socket is unattributable for life,
        // so end it here rather than merely skipping didConnect - skipping only defers the same close to
        // the socket's first frame, with a broken connection in between.
        val initiator = event.initiator ?: run {
            root.logger.warn {
                "Socket ${event.socketId} was connected by a previous deployment (its didConnect carries " +
                        "no initiator). Ending it so the client reconnects."
            }
            webSocketClose(event.socketId, WebSocketClose.GOING_AWAY)
            webSocketDynamo.clean(event.socketId)
            return APIGatewayV2HTTPResponse(204)
        }
        try {
            @Suppress("UNCHECKED_CAST")
            withMid(
                rootPath,
                event.connection as WebSocketConnectRequest<PathSpec0>,
                rootWs,
                event.socketId,
                event.storage
            ) { mid ->
                rootWs.didConnectWithMetrics(
                    rootPath,
                    root,
                    with(root) { initiator.phase(Initiator.WebSocket.Phase.Connected) },
                    mid
                )
                return APIGatewayV2HTTPResponse(200)
            }
        } catch (e: WebSocketStateGoneException) {
            root.logger.info { "Socket ${event.socketId} disconnected before didConnect could finish; discarding." }
            return APIGatewayV2HTTPResponse(204)
        } catch (e: Exception) {
            root.logger.error(e) { "Closing socket ${event.socketId} because didConnect failed." }
            webSocketClose(event.socketId, WebSocketClose.INTERNAL_ERROR)
            return APIGatewayV2HTTPResponse(500, body = e.message ?: "")
        }
    }

    suspend fun handleWebSocket(event: APIGatewayV2WebSocketRequest): APIGatewayV2HTTPResponse {
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

        // Try multiValueQueryStringParameters first, then fall back to queryStringParameters
        var queryParams = event.multiValueQueryStringParameters
            ?.entries?.flatMap { it.value.map { v -> it.key to v } }
            ?: event.queryStringParameters?.entries?.map { it.key to it.value }
            ?: listOf()

        return when (event.requestContext.routeKey) {
            "\$connect" -> {
                queryParams = queryParams.flatMap {
                    if (it.first == "path") listOf(it) + it.second.substringAfter('?').split('&')
                        .map { it.substringBefore('=') to it.substringAfter('=') }
                    else listOf(it)
                }
                val lkEvent = WebSocketConnectRequest(
                    path = RawWebSocketPath<PathSpec0>(PathSegments.EMPTY),
                    queryParameters = QueryParameters(queryParams),
                    headers = headers,
                    domain = event.requestContext.domainName,
                    protocol = "https",
                    sourceIp = event.requestContext.identity.sourceIp ?: "0.0.0.0",
                    upstreamRequestId = headers[HttpHeader.XRequestId]?.root,
                    engineSocketId = event.requestContext.connectionId
                )
                // Minted once here, at $connect, and persisted with the connection state, so the socket
                // is one identity across the five separate Lambda invocations its lifetime is made of.
                // The gateway's connection ID is not a UUID and stays in [engineSocketId], which is where
                // the join to the gateway's own logs comes from.
                val socketId = with(root) { generateRequestId() }
                val connectInitiator = Initiator.WebSocket(
                    executionId = socketId,
                    socketId = socketId,
                    path = lkEvent.path,
                    phase = Initiator.WebSocket.Phase.Connect,
                )
                try {
                    val storage = rootWs.willConnectWithMetrics(rootPath, root, connectInitiator, lkEvent)
                    val storageBytes = encoding.encodeToByteArray(rootWs.storageSerializer, storage)
                    webSocketDynamo.setState(
                        event.requestContext.connectionId,
                        lkEvent,
                        connectInitiator,
                        storageBytes,
                    )
                    try {
                        root.invokeLambda(InvokeRequest.builder().also {
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
                                            connectInitiator,
                                            AnonType(storageBytes)
                                        )
                                    )
                                )
                            )
                        }.build())
                    } catch (e: Exception) {
                        with(root) {
                            logger.error(e) { "Error invoking didConnect" }
                        }
                    }
                    root.logger.info { "WebSocket ${event.requestContext.connectionId} connected successfully." }
                    APIGatewayV2HTTPResponse(200)
                } catch (http: HttpStatusException) {
                    APIGatewayV2HTTPResponse(http.status.code, body = http.message)
                } catch (e: Exception) {
                    APIGatewayV2HTTPResponse(500, body = e.message ?: "")
                }
            }

            "\$disconnect" -> {
                try {
                    // A previous-schema row cannot have its disconnect attributed to anything, and
                    // recording the phase against a fabricated initiator would put it in the audit trail
                    // under an id that never connected.  The socket is already going away, so there is
                    // nothing to close - just drop the row.  These returns bypass the trailing `also`,
                    // hence the explicit clean.
                    val state = when (val row = webSocketDynamo.state(event.requestContext.connectionId)) {
                        SocketRow.Absent -> return APIGatewayV2HTTPResponse(204)
                        is SocketRow.Legacy -> {
                            webSocketDynamo.clean(event.requestContext.connectionId)
                            return APIGatewayV2HTTPResponse(204)
                        }

                        is SocketRow.Current -> row
                    }
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
                            root,
                            with(root) { state.initiator.phase(Initiator.WebSocket.Phase.Disconnect) },
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
                // A previous-schema row cannot be served or attributed for as long as the socket stays
                // open, so end it: the client reconnects, `${'$'}connect` writes a current row, and
                // everything after that is handled normally.  API Gateway's deleteConnection carries no
                // close code, so the client sees an abrupt close and reconnects on its own.
                val state = when (val row = webSocketDynamo.state(event.requestContext.connectionId)) {
                    SocketRow.Absent -> return APIGatewayV2HTTPResponse(204)
                    is SocketRow.Legacy -> {
                        webSocketClose(event.requestContext.connectionId, WebSocketClose.GOING_AWAY)
                        webSocketDynamo.clean(event.requestContext.connectionId)
                        return APIGatewayV2HTTPResponse(204)
                    }

                    is SocketRow.Current -> row
                }
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
                            if (with(root) { generalSettings() }.debug && state.connectRequest.queryParameters["debug"]
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
                            root.logger.error(e) { "Failed to run debug webSocket processing" }
                        }
                        rootWs.messageFromClientWithMetrics(
                            rootPath,
                            root,
                            with(root) { state.initiator.phase(Initiator.WebSocket.Phase.ClientMessage) },
                            mid,
                            WebSocketFrame(event.body)
                        )
                        APIGatewayV2HTTPResponse(200)
                    }
                } catch (e: WebSocketStateGoneException) {
                    root.logger.info { "Socket ${event.requestContext.connectionId} disconnected while its message was being processed; discarding." }
                    APIGatewayV2HTTPResponse(204)
                } catch (e: Exception) {
                    root.logger.error(e) { "Closing socket ${event.requestContext.connectionId} because message from client failed to process (route key '${event.requestContext.routeKey}')." }
                    webSocketClose(event.requestContext.connectionId, WebSocketClose.INTERNAL_ERROR)
                    APIGatewayV2HTTPResponse(500, body = e.message ?: "")
                }
            }
        }
    }
}