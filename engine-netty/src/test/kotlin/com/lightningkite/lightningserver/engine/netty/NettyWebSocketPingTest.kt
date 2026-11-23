package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.pubsub.PubSub
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import okhttp3.*
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Verifies the Netty engine applies `webSocketSettings.ping` and `pongTimeout`, as Ktor does. */
class NettyWebSocketPingTest {

    object TestServer : ServerBuilder() {
        val pubsub = setting("pubSub", PubSub.Settings())

        val idle = path.path("idle") include object : CoroutineWebSocketHandler() {
            override val pubSub = this@TestServer.pubsub

            context(serverRuntime: ServerRuntime)
            override suspend fun handle(
                request: WebSocketConnectRequest<PathSpec0>,
                waitForFullConnect: suspend () -> Unit,
                incoming: Flow<WebSocketFrame>,
                send: suspend (WebSocketFrame) -> Unit,
            ) {
                waitForFullConnect()
                delay(60.seconds)
            }
        }
    }

    private lateinit var engine: NettyEngine
    private var port: Int = 0

    @AfterTest
    fun tearDown() {
        if (::engine.isInitialized) engine.shutdown()
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
            webSocketSettings set WebSocketSettings(ping = 200.milliseconds, pongTimeout = 300.milliseconds)
            TestServer.pubsub.useDefault()
            nettyRunConfig set NettyRuntimeSettings(host = "127.0.0.1", port = port)
        }
        Thread { engine.start() }.start()
        var tries = 0
        while (engine.boundAddress == null && tries < 50) {
            Thread.sleep(100); tries++
        }
        port = engine.boundAddress!!.port
    }

    @Test
    fun peer_that_never_answers_pings_is_closed_with_1011() {
        startEngine()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(
                ("GET /idle HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray()
            )
            val input = DataInputStream(socket.getInputStream())
            val responseHead = StringBuilder()
            while (!responseHead.endsWith("\r\n\r\n")) responseHead.append(input.readUnsignedByte().toChar())
            assertEquals("HTTP/1.1 101", responseHead.substring(0, 12))

            // Server frames are unmasked and these are all short, so the header is two bytes.
            val opcodes = mutableListOf<Int>()
            var closeCode: Int? = null
            try {
                while (true) {
                    val opcode = input.readUnsignedByte() and 0x0F
                    val payload = ByteArray(input.readUnsignedByte() and 0x7F).also { input.readFully(it) }
                    opcodes += opcode
                    if (opcode == 0x8) closeCode = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                }
            } catch (_: EOFException) {
            }
            assertEquals(listOf(0x9, 0x8), opcodes, "expected one ping, then a close once it went unanswered")
            assertEquals(1011, closeCode)
        }
    }

    @Test
    fun peer_that_answers_pings_stays_connected() {
        startEngine()
        val client = OkHttpClient()
        try {
            val ended = CompletableFuture<String>()
            val ws = client.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port/idle").build(), object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    ended.complete("closed $code $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    ended.complete("failed $t")
                }
            })
            // Several ping rounds: okhttp answers each ping, so the deadline never fires.
            Thread.sleep(1_500)
            assertFalse(ended.isDone, "socket ended early: ${ended.getNow(null)}")
            ws.cancel()
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }
}
