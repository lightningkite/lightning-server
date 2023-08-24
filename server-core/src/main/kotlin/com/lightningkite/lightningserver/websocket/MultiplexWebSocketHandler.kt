package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningdb.MultiplexMessage
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.CacheHandle
import com.lightningkite.lightningserver.cache.PrefixCache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.NotFoundException
import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.time.Duration

class MultiplexWebSocketHandler(val cache: () -> Cache) : WebSockets.Handler {
    val type = "multiplex"
    private fun id(underlying: WebSocketIdentifier, channel: String): WebSocketIdentifier {
        return WebSocketIdentifier(type, "$channel/$underlying")
    }

    init {
        WebSocketIdentifier.register(
            type = type,
            send = { id, value ->
                val channel = id.substringBefore('/')
                val underlying = WebSocketIdentifier(id.substringAfter('/'))
                path(underlying, channel).get() ?: return@register false
                underlying.send(
                    Serialization.json.encodeToString(
                        MultiplexMessage(
                            channel = channel,
                            data = value,
                        )
                    )
                )
            },
            close = { id ->
                val channel = id.substringBefore('/')
                val underlying = WebSocketIdentifier(id.substringAfter('/'))
                path(underlying, channel).get() ?: return@register false
                underlying.send(
                    Serialization.json.encodeToString(
                        MultiplexMessage(
                            channel = channel,
                            end = true,
                        )
                    )
                )
            }
        )
    }

    @Serializable
    private data class ConnectionInfo(
        val domain: String,
        val protocol: String,
        val sourceIp: String,
        val headers: List<Pair<String, String>>,
    )

    private fun info(id: WebSocketIdentifier): CacheHandle<ConnectionInfo> = cache["$id-info"]
    private fun channels(id: WebSocketIdentifier): CacheHandle<Set<String>> = cache["$id-channels"]
    private fun path(id: WebSocketIdentifier, channel: String): CacheHandle<String> = cache["$id-path-$channel"]

    override suspend fun connect(event: WebSockets.ConnectEvent) {
        info(event.id).set(
            ConnectionInfo(
                domain = event.domain,
                protocol = event.protocol,
                sourceIp = event.sourceIp,
                headers = event.headers.entries + (event.queryParameter("jwt")?.let { listOf("Authorization" to it) }
                    ?: listOf())
            )
        )
    }

    override suspend fun message(event: WebSockets.MessageEvent) {
        if (event.content.isBlank()) {
            event.id.send("")
            return
        }
        val message = Serialization.json.decodeFromString<MultiplexMessage>(event.content)
        val wsIdChannel = id(event.id, message.channel)
        when {
            message.start -> {
                val info = info(event.id).get() ?: run {
                    event.id.send(
                        Serialization.json.encodeToString(
                            MultiplexMessage(
                                channel = message.channel,
                                error = "No multiplex info found."
                            )
                        )
                    )
                    return
                }
                val match = message.path?.let { WebSockets.matcher.match(it) }
                val otherHandler = match?.path?.let { WebSockets.handlers[it] }
                if (match == null || otherHandler == null) {
                    event.id.send(
                        Serialization.json.encodeToString(
                            MultiplexMessage(
                                channel = message.channel,
                                error = "No web socket found that responds to ${message.path}"
                            )
                        )
                    )
                    return
                }
                path(event.id, message.channel).set(match.path.toString())
                try {
                    Metrics.handlerPerformance(
                        WebSockets.HandlerSection(
                            match.path,
                            WebSockets.WsHandlerType.CONNECT
                        )
                    ) {
                        otherHandler.connect(
                            WebSockets.ConnectEvent(
                                path = match.path,
                                parts = match.parts,
                                wildcard = match.wildcard,
                                queryParameters = message.queryParams?.entries?.flatMap { it.value.map { v -> it.key to v } }
                                    ?: listOf(),
                                id = wsIdChannel,
                                cache = PrefixCache(event.cache, message.channel + "/"),
                                headers = HttpHeaders(info.headers),
                                domain = info.domain,
                                protocol = info.protocol,
                                sourceIp = info.sourceIp
                            )
                        )
                    }
                } catch (e: Exception) {
                    e.report(WebSockets.HandlerSection(match.path, WebSockets.WsHandlerType.CONNECT))
                    event.id.send(
                        Serialization.json.encodeToString(
                            MultiplexMessage(
                                channel = message.channel,
                                error = e.message
                            )
                        )
                    )
                    return
                }
                channels(event.id).modify(40, Duration.ofHours(8)) {
                    it?.plus(message.channel) ?: setOf(message.channel)
                }
                event.id.send(
                    Serialization.json.encodeToString(
                        MultiplexMessage(
                            channel = message.channel,
                            start = true
                        )
                    )
                )
            }

            message.end -> {
                val path = path(event.id, message.channel).get()?.let(::ServerPath)
                val otherHandler = WebSockets.handlers[path]
                if (path == null || otherHandler == null) {
                    event.id.send(
                        Serialization.json.encodeToString(
                            MultiplexMessage(
                                channel = message.channel,
                                end = true,
                                error = "Channel information not found."
                            )
                        )
                    )
                    return
                }
                path(event.id, message.channel).remove()
                channels(event.id).modify(40, timeToLive = Duration.ofHours(8)) {
                    it?.minus(message.channel) ?: setOf()
                }
                try {
                    Metrics.handlerPerformance(WebSockets.HandlerSection(path, WebSockets.WsHandlerType.DISCONNECT)) {
                        otherHandler.disconnect(
                            WebSockets.DisconnectEvent(
                                id = wsIdChannel,
                                cache = PrefixCache(event.cache, message.channel + "/"),
                            )
                        )
                    }
                    event.id.send(
                        Serialization.json.encodeToString(
                            MultiplexMessage(
                                channel = message.channel,
                                end = true
                            )
                        )
                    )
                } catch (e: Exception) {
                    e.report(WebSockets.HandlerSection(path, WebSockets.WsHandlerType.DISCONNECT))
                    event.id.send(
                        Serialization.json.encodeToString(
                            MultiplexMessage(
                                channel = message.channel,
                                end = true,
                                error = e.message
                            )
                        )
                    )
                }
            }

            message.data != null -> {
                val path = path(event.id, message.channel).get()?.let(::ServerPath)
                val otherHandler = WebSockets.handlers[path]
                if (path == null || otherHandler == null) {
                    event.id.send(
                        Serialization.json.encodeToString(
                            MultiplexMessage(
                                channel = message.channel,
                                error = "Channel information not found."
                            )
                        )
                    )
                    return
                }
                try {
                    Metrics.handlerPerformance(WebSockets.HandlerSection(path, WebSockets.WsHandlerType.MESSAGE)) {
                        otherHandler.message(
                            WebSockets.MessageEvent(
                                id = wsIdChannel,
                                cache = PrefixCache(event.cache, message.channel + "/"),
                                content = message.data!!
                            )
                        )
                    }
                } catch (e: Exception) {
                    e.report(WebSockets.HandlerSection(path, WebSockets.WsHandlerType.MESSAGE))
                    channels(event.id).modify(40, timeToLive = Duration.ofHours(8)) {
                        it?.minus(message.channel) ?: setOf()
                    }
                    path(event.id, message.channel).remove()
                    event.id.send(
                        Serialization.json.encodeToString(
                            MultiplexMessage(
                                channel = message.channel,
                                end = true,
                                error = e.message
                            )
                        )
                    )
                    try {
                        Metrics.handlerPerformance(
                            WebSockets.HandlerSection(
                                path,
                                WebSockets.WsHandlerType.DISCONNECT
                            )
                        ) {
                            otherHandler.disconnect(
                                WebSockets.DisconnectEvent(
                                    id = wsIdChannel,
                                    cache = PrefixCache(event.cache, message.channel + "/"),
                                )
                            )
                        }
                    } catch (e: Exception) {
                        e.report(WebSockets.HandlerSection(path, WebSockets.WsHandlerType.DISCONNECT))
                    }
                }
            }
        }
    }

    override suspend fun disconnect(event: WebSockets.DisconnectEvent) {
        channels(event.id).get()!!.forEach { channel ->
            try {
                val wsIdChannel = id(event.id, channel)
                val path = path(event.id, channel).get()?.let(::ServerPath)
                    ?: throw NotFoundException("No web socket handler found for channel '${channel}'")
                try {
                    val otherHandler =
                        WebSockets.handlers[path] ?: throw NotFoundException("No web socket handler found for '$path'")
                    Metrics.handlerPerformance(WebSockets.HandlerSection(path, WebSockets.WsHandlerType.DISCONNECT)) {
                        otherHandler.disconnect(
                            WebSockets.DisconnectEvent(
                                id = wsIdChannel,
                                cache = PrefixCache(event.cache, channel + "/"),
                            )
                        )
                    }
                } catch (e: Exception) {
                    e.report(WebSockets.HandlerSection(path, WebSockets.WsHandlerType.DISCONNECT))
                }
            } catch (e: Exception) {
                e.report()
            }
        }
        channels(event.id).remove()
        info(event.id).remove()
    }
}
