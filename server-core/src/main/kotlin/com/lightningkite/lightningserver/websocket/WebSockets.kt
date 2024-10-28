package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathMatcher
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.Request
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.utils.MutableMapWithChangeHandler
import com.lightningkite.uuid
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import java.util.*
import com.lightningkite.UUID

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

    var interceptConnect: WsConnectInterceptor = { r, c -> c(r) }
        private set
    var interceptorsConnect = listOf<WsConnectInterceptor>()
        set(value) {
            field = value
            // WARNING: This will melt your brain
            interceptConnect =
                interceptorsConnect.fold<WsConnectInterceptor, WsConnectInterceptor>({ request, handler ->
                    handler(request)
                }) { total, wrapper ->
                    return@fold { request, handler ->
                        total(request) { wrapper(it, handler) }
                    }
                }
        }
    var interceptMessage: WsMessageInterceptor = { r, c -> c(r) }
        private set
    var interceptorsMessage = listOf<WsMessageInterceptor>()
        set(value) {
            field = value
            // WARNING: This will melt your brain
            interceptMessage =
                interceptorsMessage.fold<WsMessageInterceptor, WsMessageInterceptor>({ request, handler ->
                    handler(request)
                }) { total, wrapper ->
                    return@fold { request, handler ->
                        total(request) { wrapper(it, handler) }
                    }
                }
        }
    var interceptDisconnect: WsDisconnectInterceptor = { r, c -> c(r) }
        private set
    var interceptorsDisconnect = listOf<WsDisconnectInterceptor>()
        set(value) {
            field = value
            // WARNING: This will melt your brain
            interceptDisconnect =
                interceptorsDisconnect.fold<WsDisconnectInterceptor, WsDisconnectInterceptor>({ request, handler ->
                    handler(request)
                }) { total, wrapper ->
                    return@fold { request, handler ->
                        total(request) { wrapper(it, handler) }
                    }
                }
        }
    var interceptSend: WsSendInterceptor = { id, content, c -> c(id, content) }
        private set
    var interceptorsSend = listOf<WsSendInterceptor>()
        set(value) {
            field = value
            // WARNING: This will melt your brain
            interceptSend =
                value.fold<WsSendInterceptor, WsSendInterceptor>({ id, content, handler ->
                    handler(id, content)
                }) { total, wrapper ->
                    return@fold { id, content, handler ->
                        total(id, content) { id, content -> wrapper(id, content, handler) }
                    }
                }
        }
    var interceptClose: WsCloseInterceptor = { id, c -> c(id) }
        private set
    var interceptorsClose = listOf<WsCloseInterceptor>()
        set(value) {
            field = value
            // WARNING: This will melt your brain
            interceptClose =
                value.fold<WsCloseInterceptor, WsCloseInterceptor>({ id, handler ->
                    handler(id)
                }) { total, wrapper ->
                    return@fold { id, handler ->
                        total(id) { id -> wrapper(id, handler) }
                    }
                }
        }

    class ConnectEvent(
        override val path: ServerPath,
        override val parts: Map<String, String>,
        override val wildcard: String? = null,
        override val queryParameters: List<Pair<String, String>>,
        val id: WebSocketIdentifier,
        val cache: Cache,
        override val headers: HttpHeaders,
        override val domain: String,
        override val protocol: String,
        override val sourceIp: String,
    ) : Request {
        fun queryParameter(key: String): String? = queryParameters.find { it.first == key }?.second

        private val cacheCalc = HashMap<Request.CacheKey<*>, Any?>()
        override suspend fun <T> cache(key: Request.CacheKey<T>): T {
            @Suppress("UNCHECKED_CAST")
            if (cacheCalc.containsKey(key)) return cacheCalc[key] as T
            val calculated = key.calculate(this)
            cacheCalc[key] = calculated
            return calculated
        }
    }

    @Deprecated("use interceptorsConnect instead", ReplaceWith("interceptorsConnect"))
    var interceptors by ::interceptorsConnect

    class MessageEvent(val id: WebSocketIdentifier, val cache: Cache, val content: String)
    class DisconnectEvent(val id: WebSocketIdentifier, val cache: Cache)

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

typealias WsInterceptor = suspend (request: WebSockets.ConnectEvent, cont: suspend (WebSockets.ConnectEvent) -> Unit) -> Unit
typealias WsConnectInterceptor = suspend (request: WebSockets.ConnectEvent, cont: suspend (WebSockets.ConnectEvent) -> Unit) -> Unit
typealias WsMessageInterceptor = suspend (request: WebSockets.MessageEvent, cont: suspend (WebSockets.MessageEvent) -> Unit) -> Unit
typealias WsDisconnectInterceptor = suspend (request: WebSockets.DisconnectEvent, cont: suspend (WebSockets.DisconnectEvent) -> Unit) -> Unit
typealias WsSendInterceptor = suspend (destination: WebSocketIdentifier, message: String, cont: suspend (destination: WebSocketIdentifier, message: String) -> Boolean) -> Boolean
typealias WsCloseInterceptor = suspend (destination: WebSocketIdentifier, cont: suspend (destination: WebSocketIdentifier) -> Boolean) -> Boolean

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
    val cache = LocalCache()
    val id = WebSocketIdentifier(uuid().toString(), "TEST")
    val req = WebSockets.ConnectEvent(
        path = this,
        parts = parts,
        wildcard = wildcard,
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        id = id,
        cache = cache,
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
                            h.message(WebSockets.MessageEvent(id, cache, it))
                        }
                    )
                )
            } catch (e: Exception) {
                error = e
            }
            println("$id Disconnecting...")
            h.disconnect(WebSockets.DisconnectEvent(id, cache))
            println("$id Disconnected.")

            error?.let { throw it }
        }
    } finally {
        WebSocketIdentifier.unregister(id.type)
    }
}