package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.engine.local.enginePubSub
import com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.webSocketSettings
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sockets whose `didConnect` has run and whose `disconnect` has not. Top-level because the builder
 * below is an object; each socket identifies itself by the id its `willConnect` minted as storage.
 */
private val liveSockets: MutableSet<String> = ConcurrentHashMap.newKeySet()
private val didConnects = AtomicInteger(0)
private val cleanupReasons = CopyOnWriteArrayList<WebSocketClose>()

/**
 * A disconnect body that takes long enough for "did shutdown wait for it" to be a real question.
 * Separate from [liveSockets] so the batch test above stays fast.
 */
private val slowDidConnect = CountDownLatch(1)
private val slowDisconnectStarted = CountDownLatch(1)
private val slowDisconnectFinished = java.util.concurrent.atomic.AtomicBoolean(false)

/** Long enough to outlast the service-disconnect loop that shutdown runs after its drain. */
private const val SLOW_DISCONNECT_MILLIS = 1_000L

private object CleanupTestServer : ServerBuilder() {
    val tracked = path.path("tracked") bind WebSocketHandler(
        storageSerializer = String.serializer(),
        // The socket's own identity, so the unwind can be checked per socket rather than by counting.
        willConnect = { "socket-" + didConnects.get() + "-" + System.nanoTime() },
        didConnect = {
            liveSockets.add(currentState)
            didConnects.incrementAndGet()
        },
        disconnect = { reason ->
            liveSockets.remove(currentState)
            cleanupReasons.add(reason)
        },
    )

    val slow = path.path("slow") bind WebSocketHandler(
        storageSerializer = String.serializer(),
        willConnect = { "slow" },
        didConnect = { slowDidConnect.countDown() },
        disconnect = { _ ->
            slowDisconnectStarted.countDown()
            kotlinx.coroutines.delay(SLOW_DISCONNECT_MILLIS)
            slowDisconnectFinished.set(true)
        },
    )
}

/**
 * Shutdown must not merely record a disconnect reason — it must actually run the handler's cleanup
 * body, undoing whatever `willConnect`/`didConnect` set up. A disconnect launched into a scope that
 * shutdown has already cancelled is born cancelled and never reaches the body at all, which looks
 * identical from outside unless the body's effect is what is asserted.
 */
class SocketShutdownCleanupTest {

    private lateinit var engine: NettyEngine
    private lateinit var client: OkHttpClient

    private fun startEngine() {
        engine = NettyEngine(CleanupTestServer.build())
        engine.settings.run {
            generalSettings.useDefault()
            secretBasis.useDefault()
            loggingSettings.useDefault()
            telemetrySettings.useDefault()
            enginePubSub.useDefault()
            engineCache.useDefault()
            webSocketSettings.useDefault()
            forceWebSocketPubSub set true
            nettyRunConfig set NettyRuntimeSettings(host = "127.0.0.1", port = 0)
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
        val deadline = System.currentTimeMillis() + 10_000
        while (engine.boundAddress == null && startupFailure.get() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        startupFailure.get()?.let { throw IllegalStateException("NettyEngine failed to start", it) }
        assertTrue(engine.boundAddress != null, "engine never bound a port")
        client = OkHttpClient()
    }

    /** Opens a socket and returns once the client handshake has completed. */
    private fun openSocket(path: String = "tracked"): WebSocket {
        val opened = CountDownLatch(1)
        val ws = client.newWebSocket(
            Request.Builder().url("ws://127.0.0.1:${engine.boundAddress!!.port}/$path").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = opened.countDown()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = opened.countDown()
            },
        )
        assertTrue(opened.await(5, TimeUnit.SECONDS), "WebSocket failed to open")
        return ws
    }

    /**
     * `didConnect` runs asynchronously after the handshake the client already saw, so waiting on the
     * client alone would let shutdown race the very setup whose unwinding is under test.
     */
    private fun awaitDidConnects(count: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (didConnects.get() < count && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(count, didConnects.get(), "server-side didConnect never ran for every socket")
    }

    @AfterTest
    fun tearDown() {
        if (::engine.isInitialized) engine.shutdown()
        if (::client.isInitialized) client.dispatcher.executorService.shutdown()
        liveSockets.clear()
        didConnects.set(0)
        cleanupReasons.clear()
    }

    /**
     * A single socket is not a useful assertion here: reverting the fix entirely still unwinds one
     * socket most of the time, because the lone `channelInactive` happens to land while the engine
     * scope is still alive. A batch is what actually discriminates — verified by reverting
     * `NettyEngine`/`LocalEngine` to their pre-fix state, which leaves most of these still live.
     */
    @Test
    fun `shutdown unwinds every open socket, exactly once each`() {
        startEngine()
        val count = 25
        repeat(count) { openSocket() }
        awaitDidConnects(count)
        assertEquals(count, liveSockets.size, "test setup: every socket should be registered as live")

        engine.shutdown()

        assertEquals(emptySet<String>(), liveSockets, "shutdown left sockets un-unwound")
        assertEquals(
            List(count) { WebSocketClose.GOING_AWAY },
            cleanupReasons.toList(),
            "each open socket should disconnect exactly once, as going away",
        )
    }

    /**
     * Shutdown must not return until the disconnect bodies it raised have actually finished.
     *
     * This is the guarantee that makes `cleanupScope` load-bearing rather than cosmetic, and the
     * batch test above cannot see it: launching the disconnects into the engine scope instead leaves
     * them joined by nothing, yet they still *usually* complete inside the incidental window between
     * the drain and `scope.cancel()` — the service-disconnect loop. A body that outlasts that window
     * is what separates "waited for" from "happened to finish in time".
     *
     * Mutation-checked both ways: reverting `cleanupScope.launch` to `scope.launch` in
     * `NettyEngine.emitDisconnect` fails this test deterministically, because the drain finds
     * `cleanupScope` empty, walks past, and cancels the body mid-`delay`.
     */
    @Test
    fun `shutdown waits for a slow disconnect body to finish`() {
        startEngine()
        openSocket("slow")
        assertTrue(slowDidConnect.await(10, TimeUnit.SECONDS), "server-side didConnect never ran")

        engine.shutdown()

        // Already counted down by the time shutdown returns — no waiting here, or this would pass
        // for a body that merely started.
        assertEquals(0L, slowDisconnectStarted.count, "the disconnect body never started at all")
        assertTrue(
            slowDisconnectFinished.get(),
            "shutdown returned while the disconnect body was still running — the disconnect is " +
                "joined by nothing and will be cancelled mid-cleanup",
        )
    }
}
