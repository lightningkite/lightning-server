package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.AnonType
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.RawPath
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable


@Serializable
public data class QueryParamWebSocketHandlerData(
    val request: WebSocketConnectRequest<*>,
    val underlyingData: AnonType
)

public class QueryParamWebSocketHandler() : WebSocketHandler<PathSpec0, QueryParamWebSocketHandlerData> {
    override val storageSerializer: KSerializer<QueryParamWebSocketHandlerData> =
        QueryParamWebSocketHandlerData.serializer()

    private class ConnectionWrapped<T>(
        val wrapped: WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>,
        val handler: WebSocketHandler<PathSpec, T>
    ) : WebSocketConnection<PathSpec, T>, ServerRuntime by wrapped {
        @Suppress("UNCHECKED_CAST")
        override val request: WebSocketConnectRequest<PathSpec>
            get() = wrapped.currentState.request as WebSocketConnectRequest<PathSpec>
        override var currentState: T = wrapped.currentState.underlyingData.value(
            wrapped.internalSerialization.kotlinBytesFormat,
            handler.storageSerializer
        )
            private set

        override suspend fun close(reason: WebSocketClose) = wrapped.close(reason)
        override suspend fun send(frame: WebSocketFrame) = wrapped.send(frame)
        override suspend fun repullState(): T =
            wrapped.repullState().underlyingData.value(
                wrapped.internalSerialization.kotlinBytesFormat,
                handler.storageSerializer
            )

        override suspend fun queueStateUpdate(modification: (T) -> T) {
            wrapped.queueStateUpdate { data ->
                val underlying =
                    data.underlyingData.value(wrapped.internalSerialization.kotlinBytesFormat, handler.storageSerializer)
                data.copy(
                    underlyingData = AnonType(
                        wrapped.internalSerialization.kotlinBytesFormat,
                        modification(underlying),
                        handler.storageSerializer
                    )
                )
            }
        }

        override suspend fun updateStateImmediately(modification: (T) -> T): T {
            wrapped.updateStateImmediately { data ->
                val underlying =
                    data.underlyingData.value(wrapped.internalSerialization.kotlinBytesFormat, handler.storageSerializer)
                data.copy(
                    underlyingData = AnonType(
                        wrapped.internalSerialization.kotlinBytesFormat,
                        modification(underlying).also { currentState = it },
                        handler.storageSerializer
                    )
                )
            }
            return currentState
        }

        override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) =
            wrapped.subscribe(topic)

        override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) = wrapped.unsubscribe(topic)

        suspend fun finalize() {
            if(request.cache.updated) {
                wrapped.updateStateImmediately { data ->
                    data.copy(request = request)
                }
            }
        }
    }

    private suspend inline fun <T> WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>.withWrapped(
        handler: WebSocketHandler<PathSpec, T>,
        action: suspend (ConnectionWrapped<T>) -> Unit
    ): WebSocketConnection<PathSpec, T> {
        val wrapped = ConnectionWrapped(this, handler)
        action(wrapped)
        wrapped.finalize()
        return wrapped
    }


    override suspend fun willConnect(
        serverRuntime: ServerRuntime,
        request: WebSocketConnectRequest<PathSpec0>
    ): QueryParamWebSocketHandlerData {
        val rawPath = request.headers["x-path"]?.root ?: request.queryParameter("path")?.substringBefore('?') ?: "/"
        val match = serverRuntime.server.endpoints.match(serverRuntime.externalSerialization.stringArrayFormat, rawPath)
            ?: throw NotFoundException("No web socket handler found for '$rawPath' - ${request.queryParameter("path")}")
        val request = run {
            val fixedQueryParameters = request.queryParameters.mapNotNull {
                if (it.first == "path") {
                    if (it.second.contains('?'))
                        it.second.substringAfter('?').substringBefore('=') to it.second.substringAfter('?')
                            .substringAfter('=')
                    else
                        null
                } else it
            }
            WebSocketConnectRequest<PathSpec>(
                path = RawPath<PathSpec>(rawPath, match),
                queryParameters = fixedQueryParameters,
                headers = request.headers,
                domain = request.domain,
                protocol = request.protocol,
                sourceIp = request.sourceIp,
                cache = request.cache,
            )
        }
        val otherHandler = serverRuntime.server.endpoints[match.pathSpec]?.websocket
            ?: throw com.lightningkite.lightningserver.NotFoundException("No web socket handler found for '$rawPath'")
        @Suppress("UNCHECKED_CAST")
        otherHandler as WebSocketHandler<PathSpec, *>
        val startData =
        // TODO: How do we handle metrics here?
//            Metrics.handlerPerformance(
//                WebSockets.HandlerContext(
//                    request.path,
//                    WebSockets.WsHandlerType.CONNECTING,
//                    null /*TODO*/
//                )
//            ) {
            otherHandler.willConnectWithMetrics(match.pathSpec, serverRuntime, request)
//            }

        @Suppress("UNCHECKED_CAST")
        return QueryParamWebSocketHandlerData(
            request,
            AnonType(serverRuntime.internalSerialization.kotlinBytesFormat, startData, otherHandler.storageSerializer as KSerializer<Any?>)
        )
    }

    override suspend fun didConnect(
        connection: WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>,
    ) {
        val innerRequest = connection.currentState.request
        val match = with(connection) { innerRequest.path.match }
        val otherHandler = match.value?.websocket
            ?: throw com.lightningkite.lightningserver.NotFoundException("No web socket handler found for '${innerRequest.path.string}'")
        @Suppress("UNCHECKED_CAST")
        otherHandler as WebSocketHandler<PathSpec, Any?>
        connection.withWrapped(otherHandler) { otherHandler.didConnectWithMetrics(match.pathSpec, it) }
    }

    override suspend fun messageFromClient(
        connection: WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>,
        frame: WebSocketFrame,
    ) {
        val innerRequest = connection.currentState.request
        val match = with(connection) { innerRequest.path.match }
        val otherHandler = match.value?.websocket
            ?: throw com.lightningkite.lightningserver.NotFoundException("No web socket handler found for '${innerRequest.path.string}'")
        @Suppress("UNCHECKED_CAST")
        otherHandler as WebSocketHandler<PathSpec, Any?>
        connection.withWrapped(otherHandler) { otherHandler.messageFromClientWithMetrics(match.pathSpec, it, frame) }
    }

    override suspend fun messageFromSubscription(
        connection: WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>,
        topic: WebSocketSubscriptionMessage<*, *>
    ) {
        val innerRequest = connection.currentState.request
        val match = with(connection) { innerRequest.path.match }
        val otherHandler = match.value?.websocket
            ?: throw com.lightningkite.lightningserver.NotFoundException("No web socket handler found for '${innerRequest.path.string}'")
        @Suppress("UNCHECKED_CAST")
        otherHandler as WebSocketHandler<PathSpec, Any?>
        connection.withWrapped(otherHandler) { otherHandler.messageFromSubscriptionWithMetrics(match.pathSpec, it, topic) }
    }

    override suspend fun disconnect(
        connection: WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>,
        reason: WebSocketClose,
    ) {
        val innerRequest = connection.currentState.request
        val match = with(connection) { innerRequest.path.match }
        val otherHandler = match.value?.websocket
            ?: throw com.lightningkite.lightningserver.NotFoundException("No web socket handler found for '${innerRequest.path.string}'")
        @Suppress("UNCHECKED_CAST")
        otherHandler as WebSocketHandler<PathSpec, Any?>
        connection.withWrapped(otherHandler) { otherHandler.disconnectWithMetrics(match.pathSpec, it, reason) }
    }
}