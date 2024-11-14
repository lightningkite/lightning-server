package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.serialization.AnonType
import com.lightningkite.lightningserver.serialization.TypeRetriever
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
data class QueryParamWebSocketHandlerData(val handlerPath: ServerPath, @Contextual val underlyingData: AnonType) {
    val handler get() = WebSockets.handlers[handlerPath] ?: throw NotFoundException("No web socket handler found for '$handlerPath'")
}

class QueryParamWebSocketHandler() : WebSocketHandler<QueryParamWebSocketHandlerData> {
    override val storageSerializer: KSerializer<QueryParamWebSocketHandlerData> =
        QueryParamWebSocketHandlerData.serializer()

    fun translateRequest(path: String, request: WebSocketConnectRequest): WebSocketConnectRequest {
        val match = WebSockets.matcher.match(path) ?: throw NotFoundException("No web socket handler found for '$path'")
        val fixedQueryParameters = request.queryParameters.mapNotNull {
            if (it.first == "path") {
                if (it.second.contains('?'))
                    it.second.substringAfter('?').substringBefore('=') to it.second.substringAfter('?')
                        .substringAfter('=')
                else
                    null
            } else it
        }
        return WebSocketConnectRequest(
            path = match.path,
            parts = match.parts,
            wildcard = match.wildcard,
            queryParameters = fixedQueryParameters,
            cache = request.cache,
            headers = request.headers,
            domain = request.domain,
            protocol = request.protocol,
            sourceIp = request.sourceIp
        )
    }

    fun <T> MidWebsocket<QueryParamWebSocketHandlerData>.wrapped(handler: WebSocketHandler<T>): MidWebsocket<T> = object: MidWebsocket<T> {
        override var currentState: T = this@wrapped.currentState.underlyingData.value(handler.storageSerializer)
            private set
        override suspend fun close(reason: WebSocketClose) = this@wrapped.close(reason)
        override suspend fun send(frame: WebSocketFrame) = this@wrapped.send(frame)
        override suspend fun repullState(): T = this@wrapped.repullState().underlyingData.value(handler.storageSerializer)
        override suspend fun queueStateUpdate(modification: (T) -> T) {
            this@wrapped.queueStateUpdate { data ->
                val underlying = data.underlyingData.value(handler.storageSerializer)
                data.copy(
                    underlyingData = AnonType(modification(underlying), handler.storageSerializer)
                )
            }
        }

        override suspend fun updateStateImmediately(modification: (T) -> T): T {
            this@wrapped.updateStateImmediately { data ->
                val underlying = data.underlyingData.value(handler.storageSerializer)
                data.copy(
                    underlyingData = AnonType(
                        modification(underlying).also { currentState = it },
                        handler.storageSerializer
                    )
                )
            }
            return currentState
        }
        override suspend fun <T> subscribe(topic: WebSocketTopic<T>) = this@wrapped.subscribe(topic)
        override suspend fun unsubscribe(topic: String) = this@wrapped.unsubscribe(topic)
    }

    override suspend fun willConnect(request: WebSocketConnectRequest): QueryParamWebSocketHandlerData {
        val other = request.headers["x-path"] ?: request.queryParameter("path")?.substringBefore('?') ?: "/"
        val request = translateRequest(other, request)
        val otherHandler = WebSockets.handlers[request.path] ?: throw NotFoundException("No web socket handler found for '$other'")
        val startData = Metrics.handlerPerformance(WebSockets.HandlerSection(request.path, WebSockets.WsHandlerType.CONNECTING)) {
            otherHandler.willConnect(request)
        }
        return QueryParamWebSocketHandlerData(request.path, AnonType(startData, otherHandler.storageSerializer as KSerializer<Any?>))
    }

    override suspend fun didConnect(
        connection: MidWebsocket<QueryParamWebSocketHandlerData>,
        request: WebSocketConnectRequest
    ) {
        val other = request.headers["x-path"] ?: request.queryParameter("path")?.substringBefore('?') ?: "/"
        val request = translateRequest(other, request)
        val otherHandler = WebSockets.handlers[request.path] as? WebSocketHandler<Any?> ?: throw NotFoundException("No web socket handler found for '$other'")
        otherHandler.didConnectTracked(request.path, connection.wrapped<Any?>(otherHandler), request)
    }

    override suspend fun messageFromClient(
        connection: MidWebsocket<QueryParamWebSocketHandlerData>,
        frame: WebSocketFrame
    ) {
        val otherHandler = connection.currentState.handler as WebSocketHandler<Any?>
        otherHandler.messageFromClientTracked(connection.currentState.handlerPath, connection.wrapped<Any?>(otherHandler), frame)
    }

    override suspend fun messageFromSubscription(
        connection: MidWebsocket<QueryParamWebSocketHandlerData>,
        topic: String,
        retrieve: TypeRetriever
    ) {
        val otherHandler = connection.currentState.handler as WebSocketHandler<Any?>
        otherHandler.messageFromSubscriptionTracked(connection.currentState.handlerPath, connection.wrapped<Any?>(otherHandler), topic, retrieve)
    }

    override suspend fun disconnect(
        connection: MidWebsocket<QueryParamWebSocketHandlerData>,
        reason: WebSocketClose
    ) {
        val otherHandler = connection.currentState.handler as WebSocketHandler<Any?>
        otherHandler.disconnectTracked(connection.currentState.handlerPath, connection.wrapped<Any?>(otherHandler), reason)
    }
}