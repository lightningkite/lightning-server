package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.AnonType
import com.lightningkite.lightningserver.MultiplexMessage
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.RawWebsocketPath
import com.lightningkite.lightningserver.pathing.path
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.collections.iterator
import kotlin.collections.plus

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

    private inner class WrappedConnection<T>(
        val wrapped: WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>,
        val channel: String,
        val handler: WebSocketHandler<PathSpec, T>
    ) : WebSocketConnection<PathSpec, T>, ServerRuntime by wrapped {
        @Suppress("UNCHECKED_CAST")
        override val request: WebSocketConnectRequest<PathSpec> get() = wrapped.currentState.map.getValue(channel).request as WebSocketConnectRequest<PathSpec>
        override var currentState: T = wrapped.currentState.map.getValue(channel).storage.value(wrapped.internalSerialization.kotlinBytesFormat, handler.storageSerializer)
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
            run { wrapped.repullState().map[channel]!!.storage.value(this.internalSerialization.kotlinBytesFormat, handler.storageSerializer) }

        override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) {
            if (topic.path(this.externalSerialization.stringArrayFormat) !in wrapped.currentState) wrapped.subscribe(topic)
            wrapped.updateStateImmediately { data ->
                data.copy(map = data.map + (channel to data.map.getValue(channel).let {
                    it.copy(topics = it.topics + topic.path(this.externalSerialization.stringArrayFormat))
                }))
            }
        }

        override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) {
            val asString = topic.path(this.externalSerialization.stringArrayFormat)
            val newstate = wrapped.updateStateImmediately { data ->
                data.copy(map = data.map + (channel to data.map.getValue(channel).let {
                    it.copy(topics = it.topics + asString)
                }))
            }
            if (asString !in newstate) wrapped.unsubscribe(topic)
        }

        override suspend fun queueStateUpdate(modification: (T) -> T) {
            wrapped.queueStateUpdate { data ->
                val underlying = data.map.getValue(channel).storage.value(this.internalSerialization.kotlinBytesFormat, handler.storageSerializer)
                data.copy(
                    map = data.map + (channel to data.map.getValue(channel)
                        .copy(storage = AnonType(this.internalSerialization.kotlinBytesFormat, modification(underlying), handler.storageSerializer)))
                )
            }
        }

        override suspend fun updateStateImmediately(modification: (T) -> T): T {
            wrapped.updateStateImmediately { data ->
                val underlying = data.map.getValue(channel).storage.value(this.internalSerialization.kotlinBytesFormat, handler.storageSerializer)
                data.copy(
                    map = data.map + (channel to data.map.getValue(channel)
                        .copy(storage = AnonType(this.internalSerialization.kotlinBytesFormat, modification(underlying).also {
                            currentState = it
                        }, handler.storageSerializer)))
                )
            }
            return currentState
        }

        suspend fun finalize() {
            if(request.cache.updated) {
                wrapped.updateStateImmediately { data ->
                    data.copy(
                        map = data.map + (channel to data.map.getValue(channel).copy(request = request))
                    )
                }
            }
        }
    }

    private suspend inline fun <T> WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>.withWrapped(
        handler: WebSocketHandler<PathSpec, T>,
        channel: String,
        action: suspend (WrappedConnection<T>) -> Unit
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
    override suspend fun didConnect(
    ): Unit = Unit

    context(connection: WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>,)
    override suspend fun messageFromClient(
        frame: WebSocketFrame
    ) {
        if ((frame as? WebSocketFrame.Text)?.content?.isBlank() == true) {
            connection.send(" ")
            return
        }
        val message = connection.externalSerialization.json.decodeFromString<MultiplexMessage>((frame as WebSocketFrame.Text).content)
        val channel = message.channel
        try {
            when {
                message.start -> {
                    val match = connection.server.endpoints.match(connection.externalSerialization.stringArrayFormat, message.path!!) { it.websocket } ?: throw NotFoundException()
                    val otherHandler = match.value
                    @Suppress("UNCHECKED_CAST")
                    otherHandler as WebSocketHandler<PathSpec, Any?>
                    val r = WebSocketConnectRequest<PathSpec>(
                        path = RawWebsocketPath<PathSpec>(PathSegments.parse(message.path!!), match),
                        queryParameters = QueryParameters(connection.request.queryParameters + (message.queryParams?.entries?.flatMap { it.value.map { v -> it.key to v } }
                            ?: listOf())),
                        headers = connection.request.headers,
                        domain = connection.request.domain,
                        protocol = connection.request.protocol,
                        sourceIp = connection.request.sourceIp,
                        cache = connection.request.cache,
                    )
                    val storage = otherHandler.willConnectWithMetrics(match.path.pathSpec, connection, r)
                    connection.updateStateImmediately {
                        it.copy(
                            map = it.map + (channel to MultiplexWebSocketHandlerConnectionInfo(
                                request = r,
                                storage = AnonType(connection.internalSerialization.kotlinBytesFormat, storage, otherHandler.storageSerializer),
                            ))
                        )
                    }
                    connection.withWrapped(otherHandler, channel) { otherHandler.didConnectWithMetrics(match.pathSpec, it) }
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
                    val info = connection.currentState.map[message.channel]!!
                    val match = with(connection) { info.request.path.match }
                    val otherHandler = match.value
                    @Suppress("UNCHECKED_CAST")
                    otherHandler as WebSocketHandler<PathSpec, Any?>
                    connection.withWrapped(otherHandler, channel) { otherHandler.disconnectWithMetrics(match.pathSpec, it, WebSocketClose.NORMAL) }
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
                    val info = connection.currentState.map[message.channel]!!
                    val match = with(connection) { info.request.path.match }
                    val otherHandler = match.value
                    @Suppress("UNCHECKED_CAST")
                    otherHandler as WebSocketHandler<PathSpec, Any?>
                    val textFrame = WebSocketFrame.Text(message.data!!)
                    connection.withWrapped(otherHandler, channel) { otherHandler.messageFromClientWithMetrics(match.pathSpec, it, textFrame) }
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
                    otherHandler.disconnectWithMetrics(match.pathSpec, it, WebSocketClose.CLOSED_ABNORMALLY)
                }
            }
            connection.queueStateUpdate { it.copy(map = it.map - channel) }
        }
    }

    context(connection: WebSocketConnection<PathSpec0, MultiplexWebSocketHandlerState>,)
    override suspend fun messageFromSubscription(
        topic: WebSocketSubscriptionMessage<*, *>
    ): Unit = with(connection) {
        for ((channel, info) in currentState.map) {
            if (info.topics.contains(topic.path(externalSerialization.stringArrayFormat))) {
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
