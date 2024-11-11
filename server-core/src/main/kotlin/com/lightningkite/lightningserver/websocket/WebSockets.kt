package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.core.ServerPathMatcher
import com.lightningkite.lightningserver.engine.UnitTestEngine
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.http.Http
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpInterceptor
import com.lightningkite.lightningserver.http.Request
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.pubsub.LocalPubSub
import com.lightningkite.lightningserver.settings.generalSettings
import com.lightningkite.lightningserver.utils.MutableMapWithChangeHandler
import com.lightningkite.lightningserver.utils.cancellingScope
import com.lightningkite.uuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import java.util.*
import kotlin.collections.set

object WebSockets {
    val handlers: MutableMap<ServerPath, WebSocketHandler<*>> =
        MutableMapWithChangeHandler<ServerPath, WebSocketHandler<*>> {
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

    var interceptors = listOf<WebSocketHandlerInterceptor>()
        set(value) {
            field = value
            // WARNING: This will melt your brain
            fullInterceptor =
                interceptors.fold<WebSocketHandlerInterceptor, WebSocketHandlerInterceptor>(WebSocketHandlerInterceptor.None) { total, wrapper ->
                    return@fold object : WebSocketHandlerInterceptor {
                        override fun <T> invoke(handler: WebSocketHandler<T>): WebSocketHandler<T> =
                            wrapper(total(handler))
                    }
                }
        }
    var fullInterceptor: WebSocketHandlerInterceptor = WebSocketHandlerInterceptor.None
        private set

    enum class WsHandlerType {
        CONNECTING, CONNECTED, MESSAGE, NOTIFY, WSSUB, DISCONNECT
    }

    data class HandlerSection(val path: ServerPath, val type: WsHandlerType) {
        override fun toString(): String = "$type $path"
    }
}

interface WebSocketHandlerInterceptor {
    operator fun <T> invoke(handler: WebSocketHandler<T>): WebSocketHandler<T>

    object None : WebSocketHandlerInterceptor {
        override fun <T> invoke(handler: WebSocketHandler<T>): WebSocketHandler<T> = handler
    }
}

data class VirtualSocket(val incoming: ReceiveChannel<WebSocketFrame>, val send: suspend (WebSocketFrame) -> Unit) {
    suspend fun send(content: String) = send(WebSocketFrame(content))
    suspend fun send(content: ByteArray) = send(WebSocketFrame(content))
}

suspend fun ServerPath.test(
    parts: Map<String, String> = mapOf(),
    wildcard: String? = null,
    queryParameters: List<Pair<String, String>> = listOf(),
    headers: HttpHeaders = HttpHeaders.EMPTY,
    domain: String = "test",
    protocol: String = "ws",
    sourceIp: String = "0.0.0.0",
    test: suspend VirtualSocket.() -> Unit,
) {
    cancellingScope {
        engine = UnitTestEngine
        val cache = LocalCache()
        val path = this@test
        val req = WebSocketConnectRequest(
            path = path,
            parts = parts,
            wildcard = wildcard,
            queryParameters = queryParameters,
            headers = headers,
            domain = domain,
            protocol = protocol,
            sourceIp = sourceIp,
            cache = cache,
        )
        val channel = Channel<WebSocketFrame>(20)
        val h = WebSockets.handlers[path]!! as WebSocketHandler<Any?>

        val id = UUID.randomUUID().toString()
        println("$id Connecting...")
        val startingState = h.willConnect(req)
        val mid = object : LocalMidWebsocket<Any?>(startingState, h, this@test, LocalPubSub, this) {
            override suspend fun <T> subscribe(topic: WebSocketTopic<T>) {
                println("$id SUBSCRIBES TO ${topic.topic}")
                super.subscribe(topic)
            }
            override suspend fun unsubscribe(topic: String) {
                println("$id NO LONGER SUBSCRIBES TO ${topic}")
                super.unsubscribe(topic)
            }

            override suspend fun send(frame: WebSocketFrame) {
                println("$id <-- $frame")
                channel.send(frame)
            }

            override suspend fun close(reason: WebSocketClose) {
                println("Manually closed")
                channel.close()
            }
        }

        println("$id Connected.")
        h.didConnect(mid, req)

        var error: Exception? = null
        try {
            test(
                VirtualSocket(
                    incoming = channel,
                    send = {
                        println("$id --> $it")
                        h.messageFromClient(mid, it)
                    }
                )
            )
        } catch (e: Exception) {
            error = e
        }
        println("$id Disconnecting...")
        h.disconnect(mid, WebSocketClose.NORMAL)
        println("$id Disconnected.")

        error?.let { throw it }
    }
}