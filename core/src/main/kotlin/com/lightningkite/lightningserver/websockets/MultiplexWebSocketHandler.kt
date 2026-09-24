package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.services.data.Unsafe
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.*

@Serializable
public data class MultiplexWebSocketHandlerState(
    val map: Map<String, MultiplexWebSocketHandlerConnectionInfo>,
) {
    internal operator fun contains(topic: String): Boolean = map.values.any { info -> info.topics.contains(topic) }
}

@Serializable
public data class MultiplexWebSocketHandlerConnectionInfo(
    val storage: AnonType,
    val topics: Set<String> = setOf(),
    val request: WebSocketConnectRequest<*>,
)

public class MultiplexWebSocketHandler() : WebSocketHandler<PathSpec0, MultiplexWebSocketHandlerState> {
    override val storageSerializer: KSerializer<MultiplexWebSocketHandlerState> get() = serializer()

    private class VirtualConnection<T>(
        val wrapped: WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>,
        val channel: String,
        val handler: WebSocketHandler<PathSpec, T>,
        internalSerialization: Serialization
    ) : WebSocketConnection<PathSpec, T> {
        private inline fun MultiplexWebSocketHandlerState.updateChannel(
            channel: String,
            change: (MultiplexWebSocketHandlerConnectionInfo) -> MultiplexWebSocketHandlerConnectionInfo,
        ): MultiplexWebSocketHandlerState {
            val existing = map[channel] ?: return this
            return copy(map = map + (channel to change(existing)))
        }

        @Suppress("UNCHECKED_CAST")
        override val request: WebSocketConnectRequest<PathSpec> get() = wrapped.currentState.map.getValue(channel).request as WebSocketConnectRequest<PathSpec>
        override var currentState: T = wrapped.currentState.map.getValue(channel).storage.value(
            internalSerialization.kotlinBytesFormat,
            handler.storageSerializer
        )
            private set

        context(server: ServerRuntime)
        override suspend fun close(reason: WebSocketClose) {
            wrapped.send(
                serverRuntime.externalSerialization.json.encodeToString(
                    MultiplexMessage(
                        channel,
                        end = true,
                        error = reason.cause?.let { it.message ?: "Unknown Error" }
                    )
                )
            )
            // This matches previous behavior.
            if (reason.isExceptional) wrapped.queueStateUpdate { it.copy(map = it.map - channel) }
            else wrapped.updateStateImmediately { it.copy(map = it.map - channel) }
        }

        context(server: ServerRuntime)
        override suspend fun send(frame: WebSocketFrame) = wrapped.send(
            server.externalSerialization.json.encodeToString(
                MultiplexMessage(
                    channel = channel,
                    data = frame.text
                )
            )
        )

        context(server: ServerRuntime)
        override suspend fun repullState(): T {
            val info = wrapped.repullState().map[channel]
                ?: throw IllegalStateException("Multiplex channel $channel was closed while it was being handled.")

            return info.storage.value(
                server.internalSerialization.kotlinBytesFormat,
                handler.storageSerializer
            )
        }

        context(server: ServerRuntime)
        override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) {
            val asString = topic.path()
            if (asString !in wrapped.currentState) wrapped.subscribe(topic)
            wrapped.updateStateImmediately { data ->
                data.updateChannel(channel) { it.copy(topics = it.topics + asString) }
            }
        }

        context(server: ServerRuntime)
        override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) {
            val asString = topic.path()
            val newState = wrapped.updateStateImmediately { data ->
                data.updateChannel(channel) { it.copy(topics = it.topics - asString) }
            }
            // Only detach the underlying subscription once no channel on this socket still wants it.
            if (asString !in newState) wrapped.unsubscribe(topic)
        }

        context(server: ServerRuntime)
        override suspend fun queueStateUpdate(modification: (T) -> T) {
            wrapped.queueStateUpdate { data ->
                data.updateChannel(channel) { info ->
                    val underlying = info.storage.value(
                        server.internalSerialization.kotlinBytesFormat,
                        handler.storageSerializer
                    )
                    info.copy(
                        storage = AnonType(
                            server.internalSerialization.kotlinBytesFormat,
                            modification(underlying),
                            handler.storageSerializer
                        )
                    )
                }
            }
        }

        context(server: ServerRuntime)
        override suspend fun updateStateImmediately(modification: (T) -> T): T {
            wrapped.updateStateImmediately { data ->
                data.updateChannel(channel) { info ->
                    val underlying = info.storage.value(
                        server.internalSerialization.kotlinBytesFormat,
                        handler.storageSerializer
                    )
                    info.copy(
                        storage = AnonType(
                            server.internalSerialization.kotlinBytesFormat,
                            modification(underlying).also {
                                currentState = it
                            },
                            handler.storageSerializer
                        )
                    )
                }
            }
            return currentState
        }

        context(server: ServerRuntime)
        suspend fun finalize() {
            // The channel can be ended while this message is still being handled, leaving nothing to write back.
            if (channel !in wrapped.currentState.map) return
            if (!request.cache.updated) return
            wrapped.updateStateImmediately { data ->
                data.updateChannel(channel) { it.copy(request = request) }
            }
        }
    }

    context(runtime: ServerRuntime)
    private suspend inline fun <T> WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>.withVirtualConnection(
        handler: WebSocketHandler<PathSpec, T>,
        channel: String,
        crossinline action: suspend (WebSocketConnection<PathSpec, T>) -> Unit,
    ): WebSocketConnection<PathSpec, T> {
        val wrapped = VirtualConnection(
            this,
            channel,
            handler,
            internalSerialization = runtime.internalSerialization
        )
        action(wrapped)
        wrapped.finalize()
        return wrapped
    }

    context(runtime: ServerRuntime)
    private fun MultiplexWebSocketHandlerConnectionInfo.getChannelHandler(): WebSocketHandler<PathSpec, Any?> {
        val handler = request.path.resolve().value
        @Suppress("UNCHECKED_CAST")
        return runtime.server.interceptIncomingSocket(handler as WebSocketHandler<PathSpec, Any?>)
    }

    context(serverRuntime: ServerRuntime)
    override suspend fun willConnect(request: WebSocketConnectRequest<PathSpec0>): MultiplexWebSocketHandlerState =
        MultiplexWebSocketHandlerState(map = emptyMap())

    context(serverRuntime: ServerRuntime)
    override suspend fun didConnect(connection: WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>): Unit =
        Unit

    context(serverRuntime: ServerRuntime)
    override suspend fun messageFromClient(
        connection: WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>,
        frame: WebSocketFrame,
    ) {
        if ((frame as? WebSocketFrame.Text)?.content?.isBlank() == true) {
            connection.send(" ")
            return
        }
        val message = when (frame) {
            is WebSocketFrame.Binary ->
                serverRuntime.externalSerialization.kotlinBytesFormat.decodeFromByteArray<MultiplexMessage>(frame.content)

            is WebSocketFrame.Text ->
                serverRuntime.externalSerialization.json.decodeFromString<MultiplexMessage>(frame.content)
        }
        try {
            when {
                message.start -> {
                    val match = serverRuntime.server.endpoints.match(
                        serverRuntime.externalSerialization.stringArrayFormat,
                        message.path ?: throw BadRequestException("No path provided to start multiplexed socket")
                    ) { it.webSocket } ?: throw NotFoundException()

                    @Suppress("UNCHECKED_CAST")
                    match as PathSpecMap.Match<WebSocketHandler<PathSpec, Any?>>

                    val channelHandler = serverRuntime.server.interceptIncomingSocket(match.value)

                    val request = connection.request.subConnection(
                        // SAFETY: match came from routing, which only pairs a handler with the spec it was registered under
                        path = @OptIn(Unsafe::class) RawWebSocketPath.fromMatch(match),
                        socketId = Execution.ID.generate(),
                        queryParameters = QueryParameters(
                            connection.request.queryParameters + message.queryParams?.entries
                                .orEmpty()
                                .flatMap { it.value.map { v -> it.key to v } }
                        ),
                    )

                    val storage = channelHandler.willConnectWithMetrics(request)
                    connection.updateStateImmediately {
                        it.copy(
                            map = it.map + (message.channel to MultiplexWebSocketHandlerConnectionInfo(
                                request = request,
                                storage = AnonType(
                                    serverRuntime.internalSerialization.kotlinBytesFormat,
                                    storage,
                                    channelHandler.storageSerializer
                                ),
                            ))
                        )
                    }

                    connection.withVirtualConnection(channelHandler, message.channel) {
                        channelHandler.didConnectWithMetrics(it)
                    }
                    connection.send(
                        WebSocketFrame(
                            serverRuntime.externalSerialization.json.encodeToString(
                                MultiplexMessage(
                                    channel = message.channel,
                                    start = true
                                )
                            )
                        )
                    )
                }

                message.end -> {
                    val info = connection.currentState.map[message.channel]
                        ?: throw NotFoundException("No open multiplex channel ${message.channel} to end.")

                    val channelHandler = info.getChannelHandler()

                    connection.withVirtualConnection(channelHandler, message.channel) {
                        channelHandler.disconnectAndClose(it, WebSocketClose.NORMAL)
                    }
                }

                message.data != null -> {
                    val info = connection.currentState.map[message.channel]
                        ?: throw NotFoundException("No open multiplex channel ${message.channel} to deliver data to.")

                    val channelHandler = info.getChannelHandler()

                    connection.withVirtualConnection(channelHandler, message.channel) {
                        channelHandler.messageFromClientWithMetrics(
                            it, WebSocketFrame.Text(
                                message.data ?: throw BadRequestException("No data provided")
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            val info = connection.currentState.map[message.channel]
            if (info == null) {
                // no active channel found, just send back an end message to make the client close
                connection.send(
                    WebSocketFrame(
                        serverRuntime.externalSerialization.json.encodeToString(
                            MultiplexMessage(
                                channel = message.channel,
                                end = true,
                                error = e.message ?: "Unknown Error"
                            )
                        )
                    )
                )
            } else {
                val channelHandler = info.getChannelHandler()
                connection.withVirtualConnection(channelHandler, message.channel) { channelConnection ->
                    channelHandler.disconnectAndClose(
                        channelConnection,
                        WebSocketClose.exceptional(e)
                    )
                }
            }
            // this may have caught a CancellationException, need to check if still active
            currentCoroutineContext().ensureActive()
        }
    }

    context(serverRuntime: ServerRuntime)
    override suspend fun messageFromSubscription(
        connection: WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>,
        topic: WebSocketSubscriptionMessage<*, *>,
    ) {
        for ((channel, info) in connection.currentState.map) {
            if (info.topics.contains(topic.path())) {
                val match = info.request.path.resolve()

                @Suppress("UNCHECKED_CAST")
                val otherHandler = serverRuntime.server.compiledWebSocketInterceptors
                    .intercept(match.value as WebSocketHandler<PathSpec, Any?>)
                connection.withVirtualConnection(otherHandler, channel) {
                    otherHandler.messageFromSubscriptionWithMetrics(it, topic)
                }
            }
        }
    }

    context(serverRuntime: ServerRuntime)
    override suspend fun disconnect(
        connection: WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>,
        reason: WebSocketClose,
    ) {
        connection.currentState.map.entries.forEach { (channel, info) ->
            val match = info.request.path.resolve()

            @Suppress("UNCHECKED_CAST")
            val otherHandler = serverRuntime.server.compiledWebSocketInterceptors
                .intercept(match.value as WebSocketHandler<PathSpec, Any?>)
            connection.withVirtualConnection(otherHandler, channel) {
                otherHandler.disconnectAndClose(it, reason)
            }
        }
    }
}
