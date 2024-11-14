package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathMatcher
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.serialization.AnonType
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.TypeRetriever
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer

@Serializable
data class MultiplexWebSocketHandlerState(
    val map: Map<String, MultiplexWebSocketHandlerConnectionInfo>,
    val domain: String,
    val protocol: String,
    val sourceIp: String,
    val headers: Map<String, String>,
) {

    fun toConnectionRequest(match: ServerPathMatcher.Match, message: MultiplexMessage): WebSocketConnectRequest {
        return WebSocketConnectRequest(
            path = match.path,
            parts = match.parts,
            wildcard = match.wildcard,
            queryParameters = message.queryParams?.entries?.flatMap { it.value.map { v -> it.key to v } }
                ?: listOf(),
            cache = LocalCache(),
            headers = HttpHeaders(headers),
            domain = domain,
            protocol = protocol,
            sourceIp = sourceIp
        )
    }

    operator fun contains(topic: String): Boolean = map.values.any { info -> info.topics.contains(topic) }
}

@Serializable
data class MultiplexWebSocketHandlerConnectionInfo(
    val handlerPath: ServerPath,
    @Contextual val storage: AnonType,
    val topics: Set<String> = setOf(),
) {
    val handler
        get() = WebSockets.handlers[handlerPath]
            ?: throw NotFoundException("No web socket handler found for '$handlerPath'")
}

class MultiplexWebSocketHandler(val cache: () -> Cache) : WebSocketHandler<MultiplexWebSocketHandlerState> {
    override val storageSerializer: KSerializer<MultiplexWebSocketHandlerState> get() = serializer()

    fun <T> MidWebsocket<MultiplexWebSocketHandlerState>.wrapped(
        channel: String,
        handler: WebSocketHandler<T>
    ): MidWebsocket<T> = object : MidWebsocket<T> {

        override var currentState: T = this@wrapped.currentState.map.getValue(channel).storage.value(handler.storageSerializer)
            private set
        override suspend fun close(reason: WebSocketClose) = this@wrapped.close(reason)
        override suspend fun send(frame: WebSocketFrame) = this@wrapped.send(
            Serialization.json.encodeToString(
                MultiplexMessage(
                    channel = channel,
                    data = frame.text
                )
            )
        )

        override suspend fun repullState(): T =
            this@wrapped.repullState().map[channel]!!.storage.value(handler.storageSerializer)

        override suspend fun <T> subscribe(topic: WebSocketTopic<T>) {
            if (topic.topic !in this@wrapped.currentState) this@wrapped.subscribe(topic)
            this@wrapped.updateStateImmediately { data ->
                data.copy(map = data.map + (channel to data.map.getValue(channel).let {
                    it.copy(topics = it.topics + topic.topic)
                }))
            }
        }

        override suspend fun unsubscribe(topic: String) {
            val newstate = this@wrapped.updateStateImmediately { data ->
                data.copy(map = data.map + (channel to data.map.getValue(channel).let {
                    it.copy(topics = it.topics + topic)
                }))
            }
            if (topic !in newstate) this@wrapped.unsubscribe(topic)
        }

        override suspend fun queueStateUpdate(modification: (T) -> T) {
            this@wrapped.queueStateUpdate { data ->
                val underlying = data.map.getValue(channel).storage.value(handler.storageSerializer)
                data.copy(
                    map = data.map + (channel to data.map.getValue(channel).copy(storage = AnonType(modification(underlying), handler.storageSerializer)))
                )
            }
        }

        override suspend fun updateStateImmediately(modification: (T) -> T): T {
            this@wrapped.updateStateImmediately { data ->
                val underlying = data.map.getValue(channel).storage.value(handler.storageSerializer)
                data.copy(
                    map = data.map + (channel to data.map.getValue(channel).copy(storage = AnonType(modification(underlying).also {
                        currentState = it
                    }, handler.storageSerializer)))
                )
            }
            return currentState
        }
    }

    override suspend fun willConnect(request: WebSocketConnectRequest): MultiplexWebSocketHandlerState =
        MultiplexWebSocketHandlerState(
            map = mapOf(),
            domain = request.domain,
            protocol = request.protocol,
            sourceIp = request.sourceIp,
            headers = request.headers.entries.associate { it },
        )

    override suspend fun didConnect(
        connection: MidWebsocket<MultiplexWebSocketHandlerState>,
        request: WebSocketConnectRequest
    ) = Unit

    override suspend fun messageFromClient(
        connection: MidWebsocket<MultiplexWebSocketHandlerState>,
        frame: WebSocketFrame
    ) {
        if ((frame as? WebSocketFrame.Text)?.content?.isBlank() == true) {
            connection.send(" ")
            return
        }
        val message = Serialization.json.decodeFromString<MultiplexMessage>((frame as WebSocketFrame.Text).content)
        val channel = message.channel
        try {
            when {
                message.start -> {
                    val match = WebSockets.matcher.match(message.path!!) ?: throw NotFoundException()
                    val otherHandler =
                        WebSockets.handlers[match.path] as? WebSocketHandler<Any?> ?: throw NotFoundException()
                    val r = connection.currentState.toConnectionRequest(match, message)
                    val storage = otherHandler.willConnectTracked(match.path, r)
                    connection.updateStateImmediately {
                        it.copy(
                            map = it.map + (channel to MultiplexWebSocketHandlerConnectionInfo(
                                handlerPath = match.path,
                                storage = AnonType(storage, otherHandler.storageSerializer)
                            ))
                        )
                    }
                    otherHandler.didConnectTracked(match.path, connection.wrapped(channel, otherHandler), r)
                    connection.send(
                        WebSocketFrame(
                            Serialization.json.encodeToString(
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
                    val otherHandler = info.handler as WebSocketHandler<Any?>
                    otherHandler.disconnectTracked(
                        info.handlerPath,
                        connection.wrapped(channel, otherHandler),
                        WebSocketClose.NORMAL
                    )
                    connection.updateStateImmediately { it.copy(map = it.map - channel) }
                    connection.send(
                        WebSocketFrame(
                            Serialization.json.encodeToString(
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
                    val otherHandler = info.handler as WebSocketHandler<Any?>
                    val frame = WebSocketFrame.Text(message.data!!)
                    otherHandler.messageFromClientTracked(
                        info.handlerPath,
                        connection.wrapped(channel, otherHandler),
                        frame
                    )
                }
            }
        } catch (e: Exception) {
            connection.send(
                Serialization.json.encodeToString(
                    MultiplexMessage(
                        channel,
                        end = true,
                        error = e.message ?: "Unknown error"
                    )
                )
            )
            connection.currentState.map[channel]?.let {
                val otherHandler = it.handler as WebSocketHandler<Any?>
                otherHandler.disconnectTracked(
                    it.handlerPath,
                    connection.wrapped(channel, otherHandler),
                    WebSocketClose.CLOSED_ABNORMALLY
                )
            }
            connection.queueStateUpdate { it.copy(map = it.map - channel) }
        }
    }

    override suspend fun messageFromSubscription(
        connection: MidWebsocket<MultiplexWebSocketHandlerState>,
        topic: String,
        retriever: TypeRetriever
    ) = with(connection) {
        for ((channel, info) in currentState.map) {
            if (info.topics.contains(topic)) {
                val otherHandler = info.handler as WebSocketHandler<Any?>
                Metrics.handlerPerformance(
                    WebSockets.HandlerSection(
                        info.handlerPath,
                        WebSockets.WsHandlerType.WSSUB
                    )
                ) {
                    otherHandler.messageFromSubscription(wrapped(channel, otherHandler), topic, retriever)
                }
            }
        }
    }

    override suspend fun disconnect(connection: MidWebsocket<MultiplexWebSocketHandlerState>, reason: WebSocketClose) =
        with(connection) {
            currentState.map.entries.forEach { (channel, it) ->
                val otherHandler = it.handler as WebSocketHandler<Any?>
                otherHandler.disconnectTracked(it.handlerPath, wrapped(channel, otherHandler), reason)
            }
        }
}
