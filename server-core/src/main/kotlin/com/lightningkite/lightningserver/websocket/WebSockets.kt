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

    private var fullAction: WsInterceptor = { r, c -> c(r) }
    var interceptors = listOf<WsInterceptor>()
        set(value) {
            field = value
            // WARNING: This will melt your brain
            fullAction = interceptors.fold<WsInterceptor, WsInterceptor>({ request, handler -> handler(request) }) { total, wrapper ->
                return@fold { request, handler ->
                    total(request) { wrapper(it, handler) }
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
    ): Request {
        fun queryParameter(key: String): String? = queryParameters.find { it.first == key }?.second

        private val cacheCalc = HashMap<Request.CacheKey<*>, Any?>()
        override suspend fun <T> cache(key: Request.CacheKey<T>): T {
            @Suppress("UNCHECKED_CAST")
            if(cacheCalc.containsKey(key)) return cacheCalc[key] as T
            val calculated = key.calculate(this)
            cacheCalc[key] = calculated
            return calculated
        }
    }

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

typealias WsInterceptor = suspend (request: Request, cont: suspend (Request) -> Unit) -> Unit

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