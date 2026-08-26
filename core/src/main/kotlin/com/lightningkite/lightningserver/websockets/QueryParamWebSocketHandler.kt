package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.AnonType
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable


@Serializable
public data class QueryParamWebSocketHandlerData(
    val request: WebSocketConnectRequest<*>,
    val underlyingData: AnonType,
)

public class QueryParamWebSocketHandler() : WebSocketHandler<PathSpec0, QueryParamWebSocketHandlerData> {
    override val storageSerializer: KSerializer<QueryParamWebSocketHandlerData> =
        QueryParamWebSocketHandlerData.serializer()

    private class ConnectionWrapped<T>(
        val runtime: ServerRuntime,
        val wrapped: WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>,
        val handler: WebSocketHandler<PathSpec, T>,
    ) : WebSocketConnection<PathSpec, T> {
        @Suppress("UNCHECKED_CAST")
        override val request: WebSocketConnectRequest<PathSpec>
            get() = wrapped.currentState.request as WebSocketConnectRequest<PathSpec>
        override var currentState: T = wrapped.currentState.underlyingData.value(
            runtime.internalSerialization.kotlinBytesFormat,
            handler.storageSerializer
        )
            private set

        override suspend fun close(reason: WebSocketClose) = wrapped.close(reason)
        override suspend fun send(frame: WebSocketFrame) = wrapped.send(frame)
        override suspend fun repullState(): T =
            wrapped.repullState().underlyingData.value(
                runtime.internalSerialization.kotlinBytesFormat,
                handler.storageSerializer
            )

        override suspend fun queueStateUpdate(modification: (T) -> T) {
            wrapped.queueStateUpdate { data ->
                val underlying =
                    data.underlyingData.value(
                        runtime.internalSerialization.kotlinBytesFormat,
                        handler.storageSerializer
                    )
                data.copy(
                    underlyingData = AnonType(
                        runtime.internalSerialization.kotlinBytesFormat,
                        modification(underlying),
                        handler.storageSerializer
                    )
                )
            }
        }

        override suspend fun updateStateImmediately(modification: (T) -> T): T {
            wrapped.updateStateImmediately { data ->
                val underlying =
                    data.underlyingData.value(
                        runtime.internalSerialization.kotlinBytesFormat,
                        handler.storageSerializer
                    )
                data.copy(
                    underlyingData = AnonType(
                        runtime.internalSerialization.kotlinBytesFormat,
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
            if (request.cache.updated) {
                wrapped.updateStateImmediately { data ->
                    data.copy(request = request)
                }
            }
        }
    }

    private suspend inline fun <T> WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>.withWrapped(
        runtime: ServerRuntime,
        handler: WebSocketHandler<PathSpec, T>,
        action: suspend (ConnectionWrapped<T>) -> Unit,
    ): WebSocketConnection<PathSpec, T> {
        val wrapped = ConnectionWrapped(runtime, this, handler)
        action(wrapped)
        wrapped.finalize()
        return wrapped
    }


    context(serverRuntime: ServerRuntime)
    override suspend fun willConnect(
        request: WebSocketConnectRequest<PathSpec0>,
    ): QueryParamWebSocketHandlerData {
        val rawPath =
            request.headers["x-path"]?.root?.substringBefore('?') ?: request.queryParameters["path"]?.substringBefore(
                '?'
            ) ?: "/"
        val match = serverRuntime.server.endpoints.match(
            serverRuntime.externalSerialization.stringArrayFormat,
            rawPath
        ) { it.webSocket }
            ?: throw NotFoundException("No web socket handler found for '$rawPath'")
        val request = run {
            val fixedQueryParameters = QueryParameters(request.queryParameters.mapNotNull {
                if (it.first == "path") {
                    if (it.second.contains('?'))
                        it.second.substringAfter('?').substringBefore('=') to it.second.substringAfter('?')
                            .substringAfter('=')
                    else
                        null
                } else it
            } + (request.headers["x-path"]?.root?.substringAfter('?')?.let { QueryParameters.parse(it).entries }
                ?: listOf()))
            WebSocketConnectRequest<PathSpec>(
                path = RawWebSocketPath<PathSpec>(PathSegments.parse(rawPath), match),
                queryParameters = fixedQueryParameters,
                headers = request.headers,
                domain = request.domain,
                protocol = request.protocol,
                sourceIp = request.sourceIp,
                // Same physical socket, only the path is rewritten, so the identity carries over.
                requestId = request.requestId,
                parentRequestId = request.parentRequestId,
                upstreamRequestId = request.upstreamRequestId,
                cache = request.cache,
            )
        }
        val otherHandler = match.value
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
            AnonType(
                serverRuntime.internalSerialization.kotlinBytesFormat,
                startData,
                otherHandler.storageSerializer as KSerializer<Any?>
            )
        )
    }

    /**
     * Resolves the handler the socket was actually opened against, which is stored on the connection
     * rather than the path because every AWS socket arrives at "/".
     */
    context(serverRuntime: ServerRuntime)
    private fun WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>.inner(): Pair<PathSpec, WebSocketHandler<PathSpec, Any?>> {
        val innerRequest = currentState.request
        val match = innerRequest.path.match
        val otherHandler = match.value
            ?: throw NotFoundException("No web socket handler found for '${innerRequest.path}'")
        @Suppress("UNCHECKED_CAST")
        return match.pathSpec to (otherHandler as WebSocketHandler<PathSpec, Any?>)
    }

    context(serverRuntime: ServerRuntime)
    override suspend fun didConnect(connection: WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>) {
        val (pathSpec, otherHandler) = connection.inner()
        connection.withWrapped(serverRuntime, otherHandler) {
            otherHandler.didConnectWithMetrics(pathSpec, serverRuntime, it)
        }
    }

    context(serverRuntime: ServerRuntime)
    override suspend fun messageFromClient(
        connection: WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>,
        frame: WebSocketFrame,
    ) {
        val (pathSpec, otherHandler) = connection.inner()
        connection.withWrapped(serverRuntime, otherHandler) {
            otherHandler.messageFromClientWithMetrics(pathSpec, serverRuntime, it, frame)
        }
    }

    context(serverRuntime: ServerRuntime)
    override suspend fun messageFromSubscription(
        connection: WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>,
        topic: WebSocketSubscriptionMessage<*, *>,
    ) {
        val (pathSpec, otherHandler) = connection.inner()
        connection.withWrapped(serverRuntime, otherHandler) {
            otherHandler.messageFromSubscriptionWithMetrics(pathSpec, serverRuntime, it, topic)
        }
    }

    context(serverRuntime: ServerRuntime)
    override suspend fun disconnect(
        connection: WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>,
        reason: WebSocketClose,
    ) {
        val (pathSpec, otherHandler) = connection.inner()
        connection.withWrapped(serverRuntime, otherHandler) {
            otherHandler.disconnectWithMetrics(pathSpec, serverRuntime, it, reason)
        }
    }
}
