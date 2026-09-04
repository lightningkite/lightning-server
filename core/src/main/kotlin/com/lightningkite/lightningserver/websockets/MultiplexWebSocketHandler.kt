package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
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
    /**
     * The virtual socket's connect initiator, kept here for the same reason the request is: a later
     * phase may run in a different process, and the socket's identity has to survive the trip.
     */
    val initiator: Initiator.WebSocket,
)

/**
 * Applies [change] to a single channel's entry, leaving the state untouched if that channel is gone.
 *
 * State updates are queued, and an engine with optimistic locking (the AWS serverless engine) re-applies
 * the queue against freshly-read state whenever it loses the lock.  By then a concurrent invocation may
 * have ended the channel.  A missing channel means the update simply has no subject any more, which is a
 * normal race rather than a broken invariant, so it must not throw.
 */
private fun MultiplexWebSocketHandlerState.updateChannel(
    channel: String,
    change: (MultiplexWebSocketHandlerConnectionInfo) -> MultiplexWebSocketHandlerConnectionInfo,
): MultiplexWebSocketHandlerState {
    val existing = map[channel] ?: return this
    return copy(map = map + (channel to change(existing)))
}

@OptIn(InternalLightningServerApi::class)
public class MultiplexWebSocketHandler() : WebSocketHandler<PathSpec0, MultiplexWebSocketHandlerState> {
    override val storageSerializer: KSerializer<MultiplexWebSocketHandlerState> get() = serializer()

    private inner class WrappedConnection<T>(
        val runtime: ServerRuntime,
        val wrapped: WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>,
        val channel: String,
        val handler: WebSocketHandler<PathSpec, T>,
    ) : WebSocketConnection<PathSpec, T> {
        @Suppress("UNCHECKED_CAST")
        override val request: WebSocketConnectRequest<PathSpec> get() = wrapped.currentState.map.getValue(channel).request as WebSocketConnectRequest<PathSpec>
        override var currentState: T = wrapped.currentState.map.getValue(channel).storage.value(
            runtime.internalSerialization.kotlinBytesFormat,
            handler.storageSerializer
        )
            private set

        override suspend fun close(reason: WebSocketClose) = wrapped.close(reason)
        override suspend fun send(frame: WebSocketFrame) = wrapped.send(
            runtime.externalSerialization.json.encodeToString(
                MultiplexMessage(
                    channel = channel,
                    data = frame.text
                )
            )
        )

        override suspend fun repullState(): T =
            run {
                val info = wrapped.repullState().map[channel]
                    ?: throw IllegalStateException("Multiplex channel $channel was closed while it was being handled.")
                info.storage.value(
                    runtime.internalSerialization.kotlinBytesFormat,
                    handler.storageSerializer
                )
            }

        override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) {
            val asString = with(runtime) { topic.path() }
            if (asString !in wrapped.currentState) wrapped.subscribe(topic)
            wrapped.updateStateImmediately { data ->
                data.updateChannel(channel) { it.copy(topics = it.topics + asString) }
            }
        }

        override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) {
            val asString = with(runtime) { topic.path() }
            val newstate = wrapped.updateStateImmediately { data ->
                data.updateChannel(channel) { it.copy(topics = it.topics - asString) }
            }
            // Only detach the underlying subscription once no channel on this socket still wants it.
            if (asString !in newstate) wrapped.unsubscribe(topic)
        }

        override suspend fun queueStateUpdate(modification: (T) -> T) {
            wrapped.queueStateUpdate { data ->
                data.updateChannel(channel) { info ->
                    val underlying = info.storage.value(
                        runtime.internalSerialization.kotlinBytesFormat,
                        handler.storageSerializer
                    )
                    info.copy(
                        storage = AnonType(
                            runtime.internalSerialization.kotlinBytesFormat,
                            modification(underlying),
                            handler.storageSerializer
                        )
                    )
                }
            }
        }

        override suspend fun updateStateImmediately(modification: (T) -> T): T {
            wrapped.updateStateImmediately { data ->
                data.updateChannel(channel) { info ->
                    val underlying = info.storage.value(
                        runtime.internalSerialization.kotlinBytesFormat,
                        handler.storageSerializer
                    )
                    info.copy(
                        storage = AnonType(
                            runtime.internalSerialization.kotlinBytesFormat,
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

        suspend fun finalize() {
            // The channel can be ended while this message is still being handled, leaving nothing to write back.
            if (channel !in wrapped.currentState.map) return
            if (!request.cache.updated) return
            wrapped.updateStateImmediately { data ->
                data.updateChannel(channel) { it.copy(request = request) }
            }
        }
    }

    private suspend inline fun <T> WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>.withWrapped(
        runtime: ServerRuntime,
        handler: WebSocketHandler<PathSpec, T>,
        channel: String,
        action: suspend (WrappedConnection<T>) -> Unit,
    ): WebSocketConnection<PathSpec, T> {
        val wrapped = WrappedConnection(runtime, this, channel, handler)
        action(wrapped)
        wrapped.finalize()
        return wrapped
    }

    context(serverRuntime: ServerRuntime)
    override suspend fun willConnect(request: WebSocketConnectRequest<PathSpec0>): MultiplexWebSocketHandlerState =
        MultiplexWebSocketHandlerState(
            map = mapOf(),
        )

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
        val message =
            serverRuntime.externalSerialization.json.decodeFromString<MultiplexMessage>((frame as WebSocketFrame.Text).content)
        val channel = message.channel
        try {
            when {
                message.start -> {
                    val match = serverRuntime.server.endpoints.match(
                        serverRuntime.externalSerialization.stringArrayFormat,
                        message.path!!
                    ) { it.webSocket } ?: throw NotFoundException()
                    // A virtual socket is a logical connection in its own right, so it must go through the webSocket
                    // interceptor chain. Taking the raw handler let every multiplexed socket bypass access logging and
                    // rate limiting, exactly as bulk sub-requests once bypassed the HTTP chain.
                    @Suppress("UNCHECKED_CAST")
                    val otherHandler = serverRuntime.server.compiledWebSocketLogicalInterceptors
                        .intercept(match.value as WebSocketHandler<PathSpec, Any?>)
                    val r = connection.request.subConnection<PathSpec>(
                        path = RawWebSocketPath<PathSpec>(PathSegments.parse(message.path!!), match),
                        queryParameters = QueryParameters(connection.request.queryParameters + (message.queryParams?.entries?.flatMap { it.value.map { v -> it.key to v } }
                            ?: listOf())),
                    )
                    val subInitiator = serverRuntime.socketInitiator.subConnection(r.path)
                    val storage =
                        otherHandler.willConnectWithMetrics(match.path.pathSpec, serverRuntime, subInitiator, r)
                    connection.updateStateImmediately {
                        it.copy(
                            map = it.map + (channel to MultiplexWebSocketHandlerConnectionInfo(
                                request = r,
                                initiator = subInitiator,
                                storage = AnonType(
                                    serverRuntime.internalSerialization.kotlinBytesFormat,
                                    storage,
                                    otherHandler.storageSerializer
                                ),
                            ))
                        )
                    }
                    connection.withWrapped(serverRuntime, otherHandler, channel) {
                        otherHandler.didConnectWithMetrics(
                            match.pathSpec,
                            serverRuntime,
                            subInitiator.phase(Initiator.WebSocket.Phase.Connected),
                            it
                        )
                    }
                    connection.send(
                        WebSocketFrame(
                            serverRuntime.externalSerialization.json.encodeToString(
                                MultiplexMessage(
                                    channel = channel,
                                    start = true
                                )
                            )
                        )
                    )
                }

                message.end -> {
                    val info = connection.currentState.map[message.channel]
                        ?: throw NotFoundException("No open multiplex channel ${message.channel} to end.")
                    val match = info.request.path.match
                    @Suppress("UNCHECKED_CAST")
                    val otherHandler = serverRuntime.server.compiledWebSocketLogicalInterceptors
                        .intercept(match.value as WebSocketHandler<PathSpec, Any?>)
                    connection.withWrapped(serverRuntime, otherHandler, channel) {
                        otherHandler.disconnectWithMetrics(
                            match.pathSpec,
                            serverRuntime,
                            info.initiator.phase(Initiator.WebSocket.Phase.Disconnect),
                            it,
                            WebSocketClose.NORMAL
                        )
                    }
                    connection.updateStateImmediately { it.copy(map = it.map - channel) }
                    connection.send(
                        WebSocketFrame(
                            serverRuntime.externalSerialization.json.encodeToString(
                                MultiplexMessage(
                                    channel = channel,
                                    end = true
                                )
                            )
                        )
                    )
                }

                message.data != null -> {
                    val info = connection.currentState.map[message.channel]
                        ?: throw NotFoundException("No open multiplex channel ${message.channel} to deliver data to.")
                    val match = info.request.path.match
                    @Suppress("UNCHECKED_CAST")
                    val otherHandler = serverRuntime.server.compiledWebSocketLogicalInterceptors
                        .intercept(match.value as WebSocketHandler<PathSpec, Any?>)
                    val textFrame = WebSocketFrame.Text(message.data!!)
                    connection.withWrapped(
                        serverRuntime,
                        otherHandler,
                        channel
                    ) {
                        otherHandler.messageFromClientWithMetrics(
                            match.pathSpec,
                            serverRuntime,
                            info.initiator.phase(Initiator.WebSocket.Phase.ClientMessage),
                            it,
                            textFrame,
                        )
                    }
                }
            }
        } catch (e: Exception) {
            connection.send(
                serverRuntime.externalSerialization.json.encodeToString(
                    MultiplexMessage(
                        channel,
                        end = true,
                        error = e.message ?: "Unknown error"
                    )
                )
            )
            connection.currentState.map[channel]?.let { info ->
                val match = info.request.path.match
                @Suppress("UNCHECKED_CAST")
                val otherHandler = serverRuntime.server.compiledWebSocketLogicalInterceptors
                    .intercept(match.value as WebSocketHandler<PathSpec, Any?>)
                connection.withWrapped(serverRuntime, otherHandler, channel) {
                    otherHandler.disconnectWithMetrics(
                        match.pathSpec,
                        serverRuntime,
                        info.initiator.phase(Initiator.WebSocket.Phase.Disconnect),
                        it,
                        ((e as? HttpStatusException)?.status ?: HttpStatus.InternalServerError).bestWebSocketCloseCode
                    )
                }
            }
            connection.queueStateUpdate { it.copy(map = it.map - channel) }
        }
    }

    context(serverRuntime: ServerRuntime)
    override suspend fun messageFromSubscription(
        connection: WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>,
        topic: WebSocketSubscriptionMessage<*, *>,
    ) {
        for ((channel, info) in connection.currentState.map) {
            if (info.topics.contains(topic.path())) {
                val match = info.request.path.match
                @Suppress("UNCHECKED_CAST")
                val otherHandler = serverRuntime.server.compiledWebSocketLogicalInterceptors
                    .intercept(match.value as WebSocketHandler<PathSpec, Any?>)
                connection.withWrapped(serverRuntime, otherHandler, channel) {
                    otherHandler.messageFromSubscriptionWithMetrics(
                        match.pathSpec,
                        serverRuntime,
                        info.initiator.phase(Initiator.WebSocket.Phase.SubscriptionMessage),
                        it,
                        topic,
                    )
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
            val match = info.request.path.match
            @Suppress("UNCHECKED_CAST")
            val otherHandler = serverRuntime.server.compiledWebSocketLogicalInterceptors
                .intercept(match.value as WebSocketHandler<PathSpec, Any?>)
            connection.withWrapped(serverRuntime, otherHandler, channel) {
                otherHandler.disconnectWithMetrics(
                    match.pathSpec,
                    serverRuntime,
                    info.initiator.phase(Initiator.WebSocket.Phase.Disconnect),
                    it,
                    reason,
                )
            }
        }
    }
}
