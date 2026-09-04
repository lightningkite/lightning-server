package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.engine.local.enginePubSub
import com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reasons the socket handler was disconnected with. Top-level because the builder below is an object. */
private val disconnectReasons = CopyOnWriteArrayList<WebSocketClose>()

private object DisconnectTestServer : ServerBuilder() {
    val mirror = path.path("mirror") bind WebSocketHandler(
        storageSerializer = Unit.serializer(),
        willConnect = { Unit },
        messageFromClient = { frame -> send(frame) },
        disconnect = { reason -> disconnectReasons.add(reason) },
    )
}

/**
 * The disconnect phase must run exactly once per socket, and must still run when it was the engine
 * shutting down that ended the socket.
 *
 * Both used to fail. A client close arrives as a `CloseWebSocketFrame`, which disconnects and then
 * calls `ctx.close()`, firing `channelInactive` — which disconnected the same socket again, because
 * nothing clears the channel's attributes in between. And every disconnect was launched on the engine
 * scope, which shutdown cancels, so a coroutine started after that point was born cancelled and never
 * ran its body: the final phase of every socket still open at shutdown was lost silently.
 */
class SocketDisconnectTest {

    private lateinit var engine: NettyEngine
    private var port: Int = 0
    private lateinit var client: OkHttpClient

    private fun startEngine() {
        ServerSocket(0).use { port = (it.localSocketAddress as InetSocketAddress).port }
        engine = NettyEngine(DisconnectTestServer.build())
        engine.settings.run {
            com.lightningkite.lightningserver.definition.generalSettings.useDefault()
            com.lightningkite.lightningserver.definition.secretBasis.useDefault()
            com.lightningkite.lightningserver.definition.loggingSettings.useDefault()
            com.lightningkite.lightningserver.definition.telemetrySettings.useDefault()
            enginePubSub.useDefault()
            engineCache.useDefault()
            com.lightningkite.lightningserver.websockets.webSocketSettings.useDefault()
            forceWebSocketPubSub set true  // the pub/sub branch is where the disconnect paths live
            nettyRunConfig set NettyRuntimeSettings(host = "127.0.0.1", port = port)
        }
        // start() blocks, so it runs on its own thread — and its failures would otherwise vanish with
        // that thread, leaving only an unexplained startup timeout.
        val startupFailure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        Thread {
            try {
                engine.start()
            } catch (t: Throwable) {
                startupFailure.set(t)
            }
        }.start()
        var tries = 0
        while (engine.boundAddress == null && startupFailure.get() == null && tries < 50) {
            Thread.sleep(100); tries++
        }
        startupFailure.get()?.let { throw IllegalStateException("NettyEngine failed to start", it) }
        check(engine.boundAddress != null) { "NettyEngine failed to start within 5 seconds" }
        port = engine.boundAddress!!.port
        client = OkHttpClient()
    }

    /** Opens a socket and returns once the server has accepted it. */
    private fun openSocket(): WebSocket {
        val opened = CountDownLatch(1)
        val ws = client.newWebSocket(
            Request.Builder().url("ws://127.0.0.1:$port/mirror").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = opened.countDown()
            },
        )
        assertTrue(opened.await(5, TimeUnit.SECONDS), "WebSocket failed to open")
        return ws
    }

    @AfterTest
    fun tearDown() {
        if (::engine.isInitialized) engine.shutdown()
        if (::client.isInitialized) client.dispatcher.executorService.shutdown()
        disconnectReasons.clear()
    }

    @Test
    fun `a client close disconnects exactly once`() {
        startEngine()
        val ws = openSocket()

        ws.close(1000, "done")
        val deadline = System.currentTimeMillis() + 5_000
        while (disconnectReasons.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertTrue(disconnectReasons.isNotEmpty(), "disconnect never ran")
        // The duplicate arrived by the other route (channelInactive after ctx.close()), so waiting only
        // for the first would pass either way. Give a second one time to show up before concluding.
        Thread.sleep(1_000)

        assertEquals(
            listOf(WebSocketClose.NORMAL),
            disconnectReasons.toList(),
            "a client-initiated close ran the handler's disconnect more than once",
        )
    }

    @Test
    fun `shutdown still disconnects an open socket`() {
        startEngine()
        openSocket()  // deliberately left open: shutdown is what has to end it

        engine.shutdown()

        // shutdown() drains the cleanup scope before returning, so the phase has already run by here.
        assertEquals(
            listOf(WebSocketClose.GOING_AWAY),
            disconnectReasons.toList(),
            "shutdown lost the socket's disconnect phase, or reported it as something other than going away",
        )
    }
}
