package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathMatcher
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.utils.MutableMapWithChangeHandler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import java.util.*

object WebSockets {
    val handlers: MutableMap<ServerPath, Handler> = MutableMapWithChangeHandler<ServerPath, Handler> {
        _matcher = null
    }
    private var _matcher: ServerPathMatcher? = null
    val matcher: ServerPathMatcher
        get() {
            return _matcher ?: run {
                val created = ServerPathMatcher(handlers.keys.asSequence())
                _matcher = created
                created
            }
        }

    data class ConnectEvent(
        val path: ServerPath,
        val parts: Map<String, String>,
        val wildcard: String? = null,
        val queryParameters: List<Pair<String, String>>,
        val id: WebSocketIdentifier,
        val headers: HttpHeaders,
        val domain: String,
        val protocol: String,
        val sourceIp: String,
    ) {
        fun queryParameter(key: String): String? = queryParameters.find { it.first == key }?.second
    }

    data class MessageEvent(val id: WebSocketIdentifier, val content: String)
    data class DisconnectEvent(val id: WebSocketIdentifier)

    enum class WsHandlerType {
        CONNECT, MESSAGE, DISCONNECT
    }

    data class HandlerSection(val path: ServerPath, val type: WsHandlerType) {
        override fun toString(): String = "$type $path"
    }

    interface Handler {
        suspend fun connect(event: ConnectEvent)
        suspend fun message(event: MessageEvent)
        suspend fun disconnect(event: DisconnectEvent)
    }
}

data class VirtualSocket(val incoming: ReceiveChannel<String>, val send: suspend (String) -> Unit)

suspend fun ServerPath.test(
    parts: Map<String, String> = mapOf(),
    wildcard: String? = null,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = generalSettings().publicUrl.substringAfter("://").substringBefore("/"),
    protocol: String = generalSettings().publicUrl.substringBefore("://"),
    sourceIp: String = "0.0.0.0",
    test: suspend VirtualSocket.() -> Unit,
) {
    val id = WebSocketIdentifier(UUID.randomUUID().toString(), "TEST")
    val req = WebSockets.ConnectEvent(
        path = this,
        parts = parts,
        wildcard = wildcard,
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        id = id
    )
    val h = WebSockets.handlers[this]!!
    val channel = Channel<String>(20)

    WebSocketIdentifier.register(
        type = id.type,
        send = { _, value ->
            println("$id <-- $value")
            channel.send(value)
            true
        },
        close = {
            channel.close()
            true
        }
    )

    try {
        coroutineScope {
            println("$id Connecting...")
            h.connect(req)
            println("$id Connected.")

            var error: Exception? = null
            try {
                test(
                    VirtualSocket(
                        incoming = channel,
                        send = {
                            println("$id --> $it")
                            h.message(WebSockets.MessageEvent(id, it))
                        }
                    )
                )
            } catch (e: Exception) {
                error = e
            }
            println("$id Disconnecting...")
            h.disconnect(WebSockets.DisconnectEvent(id))
            println("$id Disconnected.")

            error?.let { throw it }
        }
    } finally {
        WebSocketIdentifier.unregister(id.type)
    }
}