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

public class MultiplexWebSocketHandler() : WebSocketHandler<PathSpec0, MultiplexWebSocketHandlerState> {
    override val storageSerializer: KSerializer<MultiplexWebSocketHandlerState> get() = serializer()

    private inner class WrappedConnection<T>(
        val wrapped: WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>,
        val channel: String,
        val handler: WebSocketHandler<PathSpec, T>,
    ) : WebSocketConnection<PathSpec, T>, ServerRuntime by wrapped {
        @Suppress("UNCHECKED_CAST")
        override val request: WebSocketConnectRequest<PathSpec> get() = wrapped.currentState.map.getValue(channel).request as WebSocketConnectRequest<PathSpec>
        override var currentState: T = wrapped.currentState.map.getValue(channel).storage.value(
            wrapped.internalSerialization.kotlinBytesFormat,
            handler.storageSerializer
        )
            private set

        override suspend fun close(reason: WebSocketClose) = wrapped.close(reason)
        override suspend fun send(frame: WebSocketFrame) = wrapped.send(
            externalSerialization.json.encodeToString(
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
                    this.internalSerialization.kotlinBytesFormat,
                    handler.storageSerializer
                )
            }

        override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) {
            if (topic.path() !in wrapped.currentState) wrapped.subscribe(topic)
            wrapped.updateStateImmediately { data ->
                data.updateChannel(channel) { it.copy(topics = it.topics + topic.path()) }
            }
        }

        override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) {
            val asString = topic.path()
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
                        this.internalSerialization.kotlinBytesFormat,
                        handler.storageSerializer
                    )
                    info.copy(
                        storage = AnonType(
                            this.internalSerialization.kotlinBytesFormat,
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
                        this.internalSerialization.kotlinBytesFormat,
                        handler.storageSerializer
                    )
                    info.copy(
                        storage = AnonType(
                            this.internalSerialization.kotlinBytesFormat,
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
        handler: WebSocketHandler<PathSpec, T>,
        channel: String,
        action: suspend (WrappedConnection<T>) -> Unit,
    ): WebSocketConnection<PathSpec, T> {
        val wrapped = WrappedConnection(this, channel, handler)
        action(wrapped)
        wrapped.finalize()
        return wrapped
    }

    context(serverRuntime: ServerRuntime)
    override suspend fun willConnect(request: WebSocketConnectRequest<PathSpec0>): MultiplexWebSocketHandlerState =
        MultiplexWebSocketHandlerState(
            map = mapOf(),
        )

    context(connection: WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>)
    override suspend fun didConnect(): Unit = Unit

    context(connection: WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>)
    override suspend fun messageFromClient(
        frame: WebSocketFrame,
    ) {
        if ((frame as? WebSocketFrame.Text)?.content?.isBlank() == true) {
            connection.send(" ")
            return
        }
        val message =
            connection.externalSerialization.json.decodeFromString<MultiplexMessage>((frame as WebSocketFrame.Text).content)
        val channel = message.channel
        try {
            when {
                message.start -> {
                    val match = connection.server.endpoints.match(
                        connection.externalSerialization.stringArrayFormat,
                        message.path!!
                    ) { it.websocket } ?: throw NotFoundException()
                    val otherHandler = match.value
                    @Suppress("UNCHECKED_CAST")
                    otherHandler as WebSocketHandler<PathSpec, Any?>
                    val r = connection.request.subConnection<PathSpec>(
                        path = RawWebsocketPath<PathSpec>(PathSegments.parse(message.path!!), match),
                        queryParameters = QueryParameters(connection.request.queryParameters + (message.queryParams?.entries?.flatMap { it.value.map { v -> it.key to v } }
                            ?: listOf())),
                    )
                    val storage = otherHandler.willConnectWithMetrics(match.path.pathSpec, connection, r)
                    connection.updateStateImmediately {
                        it.copy(
                            map = it.map + (channel to MultiplexWebSocketHandlerConnectionInfo(
                                request = r,
                                storage = AnonType(
                                    connection.internalSerialization.kotlinBytesFormat,
                                    storage,
                                    otherHandler.storageSerializer
                                ),
                            ))
                        )
                    }
                    connection.withWrapped(otherHandler, channel) {
                        otherHandler.didConnectWithMetrics(
                            match.pathSpec,
                            it
                        )
                    }
                    connection.send(
                        WebSocketFrame(
                            connection.externalSerialization.json.encodeToString(
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
                    val match = with(connection) { info.request.path.match }
                    val otherHandler = match.value
                    @Suppress("UNCHECKED_CAST")
                    otherHandler as WebSocketHandler<PathSpec, Any?>
                    connection.withWrapped(otherHandler, channel) {
                        otherHandler.disconnectWithMetrics(
                            match.pathSpec,
                            it,
                            WebSocketClose.NORMAL
                        )
                    }
                    connection.updateStateImmediately { it.copy(map = it.map - channel) }
                    connection.send(
                        WebSocketFrame(
                            connection.externalSerialization.json.encodeToString(
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
                    val match = with(connection) { info.request.path.match }
                    val otherHandler = match.value
                    @Suppress("UNCHECKED_CAST")
                    otherHandler as WebSocketHandler<PathSpec, Any?>
                    val textFrame = WebSocketFrame.Text(message.data!!)
                    connection.withWrapped(
                        otherHandler,
                        channel
                    ) { otherHandler.messageFromClientWithMetrics(match.pathSpec, it, textFrame) }
                }
            }
        } catch (e: Exception) {
            connection.send(
                connection.externalSerialization.json.encodeToString(
                    MultiplexMessage(
                        channel,
                        end = true,
                        error = e.message ?: "Unknown error"
                    )
                )
            )
            connection.currentState.map[channel]?.let { info ->
                val match = with(connection) { info.request.path.match }
                val otherHandler = match.value
                @Suppress("UNCHECKED_CAST")
                otherHandler as WebSocketHandler<PathSpec, Any?>
                connection.withWrapped(otherHandler, channel) {
                    otherHandler.disconnectWithMetrics(
                        match.pathSpec,
                        it,
                        ((e as? HttpStatusException)?.status ?: HttpStatus.InternalServerError).bestWebsocketCloseCode
                    )
                }
            }
            connection.queueStateUpdate { it.copy(map = it.map - channel) }
        }
    }

    context(connection: WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>)
    override suspend fun messageFromSubscription(
        topic: WebSocketSubscriptionMessage<*, *>,
    ): Unit = with(connection) {
        for ((channel, info) in currentState.map) {
            if (info.topics.contains(topic.path())) {
                val match = with(connection) { info.request.path.match }
                val otherHandler = match.value
                @Suppress("UNCHECKED_CAST")
                otherHandler as WebSocketHandler<PathSpec, Any?>
                connection.withWrapped(otherHandler, channel) {
                    otherHandler.messageFromSubscriptionWithMetrics(match.pathSpec, it, topic)
                }
            }
        }
    }

    context(connection: WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>)
    override suspend fun disconnect(reason: WebSocketClose): Unit =
        with(connection) {
            currentState.map.entries.forEach { (channel, info) ->
                val match = with(connection) { info.request.path.match }
                val otherHandler = match.value
                @Suppress("UNCHECKED_CAST")
                otherHandler as WebSocketHandler<PathSpec, Any?>
                connection.withWrapped(otherHandler, channel) {
                    otherHandler.disconnectWithMetrics(match.pathSpec, it, reason)
                }
            }
        }
}
