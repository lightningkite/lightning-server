package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.engine.local.EngineReliabilitySettings
import com.lightningkite.lightningserver.engine.local.WsOversizePolicy
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.pubsub.PubSub
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import okhttp3.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies WebSocket inbound backpressure (2.10): when the peer floods frames faster than the handler
 * consumes them and the bounded inbound buffer overflows under the default [WsOversizePolicy.CLOSE],
 * the engine closes the socket with WebSocket close code 1009 (message too big).
 *
 * Integration-only: depends on the real Netty frame reader feeding the bounded channel over a socket.
 */
class NettyWebSocketBackpressureTest {

    object TestServer : ServerBuilder() {
        val pubsub = setting("pubSub", PubSub.Settings())

        // A direct handler that connects but never drains `incoming`, so the bounded buffer fills.
        val stuck = path.path("stuck") include object : CoroutineWebsocketHandler() {
            override val pubSub = this@TestServer.pubsub

            context(serverRuntime: ServerRuntime)
            override suspend fun handle(
                request: WebSocketConnectRequest<PathSpec0>,
                waitForFullConnect: suspend () -> Unit,
                incoming: Flow<WebSocketFrame>,
                send: suspend (WebSocketFrame) -> Unit,
            ) {
                waitForFullConnect()
                // Intentionally never collect `incoming`; just block so frames pile up.
                delay(60.seconds)
            }
        }
    }

    private lateinit var engine: NettyEngine
    private var port: Int = 0
    private lateinit var client: OkHttpClient

    @AfterTest
    fun tearDown() {
        if (::engine.isInitialized) engine.shutdown()
        if (::client.isInitialized) client.dispatcher.executorService.shutdown()
    }

    private fun startEngine() {
        ServerSocket(0).use { port = (it.localSocketAddress as InetSocketAddress).port }
        engine = NettyEngine(TestServer.build())
        engine.settings.run {
            com.lightningkite.lightningserver.definition.generalSettings.useDefault()
            com.lightningkite.lightningserver.definition.secretBasis.useDefault()
            com.lightningkite.lightningserver.definition.telemetrySettings.useDefault()
            com.lightningkite.lightningserver.definition.loggingSettings.useDefault()
            com.lightningkite.lightningserver.engine.local.enginePubSub.useDefault()
            com.lightningkite.lightningserver.engine.local.engineCache.useDefault()
            com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub.useDefault()
            TestServer.pubsub.useDefault()
            nettyRunConfig set NettyRuntimeSettings(
                host = "127.0.0.1",
                port = port,
                reliability = EngineReliabilitySettings(
                    webSocketInboundBuffer = 1,
                    webSocketOversizePolicy = WsOversizePolicy.CLOSE,
                ),
            )
        }
        Thread { engine.start() }.start()
        var tries = 0
        while (engine.boundAddress == null && tries < 50) {
            Thread.sleep(100); tries++
        }
        port = engine.boundAddress!!.port
        client = OkHttpClient()
    }

    @Test
    fun flooding_overflows_buffer_and_closes_with_1009() {
        startEngine()
        val closeCode = CompletableFuture<Int>()
        val keepSending = AtomicBoolean(true)
        val request = Request.Builder().url("ws://127.0.0.1:$port/stuck").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Flood frames from a background thread so the okhttp reader thread stays free to
                // observe the server's 1009 close frame. Stop as soon as a close/failure is seen to
                // avoid writing into an already-closed socket (which surfaces as an opaque reset).
                Thread {
                    var i = 0
                    while (keepSending.get() && i < 5000) {
                        webSocket.send("frame-${i++}")
                        Thread.sleep(2)
                    }
                }.start()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                keepSending.set(false)
                closeCode.complete(code)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                keepSending.set(false)
                closeCode.complete(code)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                keepSending.set(false)
                closeCode.completeExceptionally(t)
            }
        })

        val code = closeCode.get(15, TimeUnit.SECONDS)
        assertEquals(1009, code, "expected WebSocket close code 1009 on inbound buffer overflow")
        ws.cancel()
    }
}
