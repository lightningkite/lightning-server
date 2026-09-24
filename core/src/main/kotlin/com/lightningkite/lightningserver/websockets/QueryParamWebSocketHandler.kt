package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.AnonType
import com.lightningkite.lightningserver.InternalLightningServerApi
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

@OptIn(InternalLightningServerApi::class)
public class QueryParamWebSocketHandler() : WebSocketHandler<PathSpec0, QueryParamWebSocketHandlerData> {
    override val storageSerializer: KSerializer<QueryParamWebSocketHandlerData> =
        QueryParamWebSocketHandlerData.serializer()

    context(runtime: ServerRuntime)
    private suspend fun <T> WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>.withWrapped(
        handler: WebSocketHandler<PathSpec, T>,
        action: suspend (WebSocketConnection<PathSpec, T>) -> Unit,
    ): WebSocketConnection<PathSpec, T> {
        class ConnectionWrapped(
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

            context(server: ServerRuntime)
            override suspend fun close(reason: WebSocketClose) = wrapped.close(reason)

            context(server: ServerRuntime)
            override suspend fun send(frame: WebSocketFrame) = wrapped.send(frame)

            context(server: ServerRuntime)
            override suspend fun repullState(): T =
                wrapped.repullState().underlyingData.value(
                    runtime.internalSerialization.kotlinBytesFormat,
                    handler.storageSerializer
                )

            context(server: ServerRuntime)
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

            context(server: ServerRuntime)
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

            context(server: ServerRuntime)
            override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) =
                wrapped.subscribe(topic)

            context(server: ServerRuntime)
            override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) = wrapped.unsubscribe(topic)

            suspend fun finalize() {
                if (request.cache.updated) {
                    wrapped.updateStateImmediately { data ->
                        data.copy(request = request)
                    }
                }
            }
        }

        val wrapped = ConnectionWrapped(this, handler)
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
            // Same physical socket, only the path is rewritten, so the socket id carries over.
            request.withPath(
                path = RawWebSocketPath<PathSpec>(PathSegments.parse(rawPath), match),
                queryParameters = fixedQueryParameters,
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
            otherHandler.willConnectWithMetrics(request)
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
    private fun WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>.inner(): WebSocketHandler<PathSpec, Any?> {
        val innerRequest = currentState.request
        val otherHandler = innerRequest.path.tryResolve()?.value
            ?: throw NotFoundException("No web socket handler found for '${innerRequest.path}'")
        @Suppress("UNCHECKED_CAST")
        return otherHandler as WebSocketHandler<PathSpec, Any?>
    }

    context(serverRuntime: ServerRuntime)
    override suspend fun didConnect(connection: WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>) {
        val otherHandler = connection.inner()
        connection.withWrapped(otherHandler) {
            otherHandler.didConnectWithMetrics(it)
        }
    }

    context(serverRuntime: ServerRuntime)
    override suspend fun messageFromClient(
        connection: WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>,
        frame: WebSocketFrame,
    ) {
        val otherHandler = connection.inner()
        connection.withWrapped(otherHandler) {
            otherHandler.messageFromClientWithMetrics(it, frame)
        }
    }

    context(serverRuntime: ServerRuntime)
    override suspend fun messageFromSubscription(
        connection: WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>,
        topic: WebSocketSubscriptionMessage<*, *>,
    ) {
        val otherHandler = connection.inner()
        connection.withWrapped(otherHandler) {
            otherHandler.messageFromSubscriptionWithMetrics(it, topic)
        }
    }

    context(serverRuntime: ServerRuntime)
    override suspend fun disconnect(
        connection: WebSocketConnection<PathSpec0, QueryParamWebSocketHandlerData>,
        reason: WebSocketClose,
    ) {
        val otherHandler = connection.inner()
        connection.withWrapped(otherHandler) {
            otherHandler.disconnectAndClose(it, reason)
        }
    }
}
