package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.serialization.AnonType
import com.lightningkite.lightningserver.serialization.TypeRetriever
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

@Serializable
data class QueryParamWebSocketHandlerData(val request: WebSocketConnectRequest, @Contextual val underlyingData: AnonType) {
    val handler get() = WebSockets.handlers[request.path]
    val handlerPath get() = request.path
}

class QueryParamWebSocketHandler() : WebSocketHandler<QueryParamWebSocketHandlerData> {
    override val storageSerializer: KSerializer<QueryParamWebSocketHandlerData> =
        QueryParamWebSocketHandlerData.serializer()

    fun <T> WebSocketConnection<QueryParamWebSocketHandlerData>.wrapped(handler: WebSocketHandler<T>): WebSocketConnection<T> = run {
        object : WebSocketConnection<T> {
            override val request: WebSocketConnectRequest get() = this@wrapped.currentState.request
            override var currentState: T = this@wrapped.currentState.underlyingData.value(handler.storageSerializer)
                private set

            override suspend fun close(reason: WebSocketClose) = this@wrapped.close(reason)
            override suspend fun send(frame: WebSocketFrame) = this@wrapped.send(frame)
            override suspend fun repullState(): T =
                this@wrapped.repullState().underlyingData.value(handler.storageSerializer)

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
    }
    override suspend fun willConnect(request: WebSocketConnectRequest): QueryParamWebSocketHandlerData {
        val rawPath = request.headers["x-path"] ?: request.queryParameter("path")?.substringBefore('?') ?: "/"
        val request = run {
            val match = WebSockets.matcher.match(rawPath)
                ?: throw NotFoundException("No web socket handler found for '$rawPath' - ${request.queryParameter("path")}")
            val fixedQueryParameters = request.queryParameters.mapNotNull {
                if (it.first == "path") {
                    if (it.second.contains('?'))
                        it.second.substringAfter('?').substringBefore('=') to it.second.substringAfter('?')
                            .substringAfter('=')
                    else
                        null
                } else it
            }
            request.copy(
                path = match.path,
                parts = match.parts,
                wildcard = match.wildcard,
                queryParameters = fixedQueryParameters,
            )
        }
        val otherHandler =
            WebSockets.handlers[request.path] ?: throw NotFoundException("No web socket handler found for '$rawPath'")
        val startData =
            Metrics.handlerPerformance(
                WebSockets.HandlerContext(
                    request.path,
                    WebSockets.WsHandlerType.CONNECTING,
                    null /*TODO*/
                )
            ) {
                otherHandler.willConnect(request)
            }
        return QueryParamWebSocketHandlerData(
            request,
            AnonType(startData, otherHandler.storageSerializer as KSerializer<Any?>)
        )
    }

    override suspend fun didConnect(
        connection: WebSocketConnection<QueryParamWebSocketHandlerData>,
    ) {
        val innerRequest = connection.currentState.request
        val otherHandler = WebSockets.handlers[innerRequest.path] as? WebSocketHandler<Any?>
            ?: throw NotFoundException("No web socket handler found - makes no sense.")
        otherHandler.didConnectTracked(connection.request.path, connection.wrapped<Any?>(otherHandler))
    }

    override suspend fun messageFromClient(
        connection: WebSocketConnection<QueryParamWebSocketHandlerData>,
        frame: WebSocketFrame,
    ) {
        val otherHandler = connection.currentState.handler as WebSocketHandler<Any?>
        otherHandler.messageFromClientTracked(
            connection.currentState.handlerPath,
            connection.wrapped<Any?>(otherHandler),
            frame
        )
    }

    override suspend fun messageFromSubscription(
        connection: WebSocketConnection<QueryParamWebSocketHandlerData>,
        topic: String,
        retrieve: TypeRetriever,
    ) {
        val otherHandler = connection.currentState.handler as WebSocketHandler<Any?>
        otherHandler.messageFromSubscriptionTracked(
            connection.currentState.handlerPath,
            connection.wrapped<Any?>(otherHandler),
            topic,
            retrieve
        )
    }

    override suspend fun disconnect(
        connection: WebSocketConnection<QueryParamWebSocketHandlerData>,
        reason: WebSocketClose,
    ) {
        val otherHandler = connection.currentState.handler as WebSocketHandler<Any?>
        otherHandler.disconnectTracked(
            connection.currentState.handlerPath,
            connection.wrapped<Any?>(otherHandler),
            reason
        )
    }
}