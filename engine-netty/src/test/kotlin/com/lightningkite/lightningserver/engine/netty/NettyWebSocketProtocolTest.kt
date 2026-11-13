package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.engine.local.EngineReliabilitySettings
import com.lightningkite.lightningserver.engine.local.WsOversizePolicy
import com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.data.DataSize.Companion.kibibytes
import com.lightningkite.services.pubsub.PubSub
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Deflater
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val FLOOD_FRAMES = 4_000
private const val FLOOD_FRAME_BYTES = 64 * 1024
private const val SLOW_FRAMES = 500

/** Few and small enough that all of them, and a close frame behind them, arrive in a single read. */
private const val CLOSE_RACE_FRAMES = 50

/**
 * One masked client frame, as a client writes it. Payloads up to 64 KiB.
 *
 * @param rsv1 marks the payload as compressed with permessage-deflate.
 */
private fun clientFrame(opcode: Int, payload: ByteArray, fin: Boolean = true, rsv1: Boolean = false): ByteArray {
    require(payload.size <= 0xFFFF)
    val out = ByteArrayOutputStream()
    val mask = byteArrayOf(1, 2, 3, 4)
    out.write((if (fin) 0x80 else 0) or (if (rsv1) 0x40 else 0) or opcode)
    if (payload.size < 126) {
        out.write(0x80 or payload.size)
    } else {
        out.write(0x80 or 126)
        out.write(payload.size shr 8)
        out.write(payload.size and 0xFF)
    }
    out.write(mask)
    out.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
    return out.toByteArray()
}

/** [size] zero bytes compressed the way permessage-deflate sends them: a few KiB that inflate to all of it. */
private fun deflateBomb(size: Int): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
    deflater.setInput(ByteArray(size))
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val written = deflater.deflate(buffer, 0, buffer.size, Deflater.SYNC_FLUSH)
        out.write(buffer, 0, written)
        if (written < buffer.size && deflater.needsInput()) break
    }
    deflater.end()
    // permessage-deflate leaves off the empty block a sync flush ends with.
    return out.toByteArray().let { it.copyOf(it.size - 4) }
}

/** Frames the `flood` handler has finished sending. Top-level because the builder below is an object. */
private val floodSent = AtomicInteger(0)

/** Texts the `slow` handler received, in arrival order. */
private val slowReceived = CopyOnWriteArrayList<String>()

private object ProtocolTestServer : ServerBuilder() {
    val pubsub = setting("pubSub", PubSub.Settings())

    val hello = path.path("hello").get bind HttpHandler<PathSpec0> { HttpResponse.plainText("hello") }

    val echo = path.path("echo") bind WebSocketHandler(
        storageSerializer = Unit.serializer(),
        willConnect = { Unit },
        messageFromClient = { frame -> send(frame) },
    )

    // The same, driven directly rather than through pub/sub.
    val directEcho = path.path("direct-echo") include object : CoroutineWebSocketHandler() {
        override val pubSub = this@ProtocolTestServer.pubsub

        context(serverRuntime: ServerRuntime)
        override suspend fun handle(
            request: WebSocketConnectRequest<PathSpec0>,
            waitForFullConnect: suspend () -> Unit,
            incoming: Flow<WebSocketFrame>,
            send: suspend (WebSocketFrame) -> Unit,
        ) {
            waitForFullConnect()
            incoming.collect { send(it) }
        }
    }

    // Sends far more than any socket buffer holds, counting each send that returns.
    val flood = path.path("flood") include object : CoroutineWebSocketHandler() {
        override val pubSub = this@ProtocolTestServer.pubsub

        context(serverRuntime: ServerRuntime)
        override suspend fun handle(
            request: WebSocketConnectRequest<PathSpec0>,
            waitForFullConnect: suspend () -> Unit,
            incoming: Flow<WebSocketFrame>,
            send: suspend (WebSocketFrame) -> Unit,
        ) {
            waitForFullConnect()
            val payload = ByteArray(FLOOD_FRAME_BYTES)
            repeat(FLOOD_FRAMES) {
                send(WebSocketFrame.Binary(payload))
                floodSent.incrementAndGet()
            }
        }
    }

    // Lets frames pile up before reading any of them, then reads them all.
    val slow = path.path("slow") include object : CoroutineWebSocketHandler() {
        override val pubSub = this@ProtocolTestServer.pubsub

        context(serverRuntime: ServerRuntime)
        override suspend fun handle(
            request: WebSocketConnectRequest<PathSpec0>,
            waitForFullConnect: suspend () -> Unit,
            incoming: Flow<WebSocketFrame>,
            send: suspend (WebSocketFrame) -> Unit,
        ) {
            waitForFullConnect()
            delay(1.seconds)
            incoming.collect { frame -> if (frame is WebSocketFrame.Text) slowReceived.add(frame.content) }
        }
    }
}

/**
 * WebSocket behavior the engine owes its clients at the protocol level, each of which it used to get
 * wrong: fragmented messages were truncated to their first frame, a client's close was answered by
 * dropping the connection, the configured pings were never sent, a slow reader let the outbound buffer
 * grow without limit, and [WsOversizePolicy.SUSPEND] did not actually hold the peer back.
 */
class NettyWebSocketProtocolTest {

    private lateinit var engine: NettyEngine
    private var port: Int = 0
    private val okHttp = lazy { OkHttpClient() }

    private fun startEngine(
        forcePubSub: Boolean = false,
        webSocket: WebSocketSettings = WebSocketSettings(),
        reliability: EngineReliabilitySettings = EngineReliabilitySettings(shutdownDrainTimeout = 1.seconds),
        compression: Boolean = false,
    ) {
        engine = NettyEngine(ProtocolTestServer.build())
        engine.useFrameworkDefaults()
        engine.settings.run {
            ProtocolTestServer.pubsub.useDefault()
            forceWebSocketPubSub set forcePubSub
            webSocketSettings set webSocket
            nettyRunConfig set NettyRuntimeSettings(
                host = "127.0.0.1",
                port = 0,
                webSocketCompression = compression,
                reliability = reliability,
            )
        }
        port = engine.startInBackground()
    }

    /** An inbound buffer of one frame that holds the peer back when it is full. */
    private val suspendAfterOneFrame = EngineReliabilitySettings(
        shutdownDrainTimeout = 1.seconds,
        webSocketInboundBuffer = 1,
        webSocketOversizePolicy = WsOversizePolicy.SUSPEND,
    )

    @AfterTest
    fun tearDown() {
        if (::engine.isInitialized) engine.shutdown()
        if (okHttp.isInitialized()) okHttp.value.dispatcher.executorService.shutdown()
        floodSent.set(0)
        slowReceived.clear()
    }

    /**
     * A WebSocket client written against the wire format, for the things a well-behaved client
     * library will not do: fragment a small message, ignore pings, or stop reading.
     */
    private inner class RawSocket(
        path: String,
        /** Further request headers, each ending in CRLF. */
        extraHeaders: String = "",
        /** A whole GET request sent ahead of the upgrade, in the same write, and answered first. */
        pipelinedBefore: String? = null,
    ) : AutoCloseable {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        private val input = DataInputStream(socket.getInputStream())
        private val output = socket.getOutputStream()

        init {
            val before = pipelinedBefore?.let { "GET $it HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n" } ?: ""
            output.write(
                (before + "GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n$extraHeaders\r\n").toByteArray()
            )
            output.flush()
            if (pipelinedBefore != null) {
                val first = readLine()
                assertTrue(first.startsWith("HTTP/1.1 200"), "the request ahead of the upgrade failed: $first")
                var length = 0
                while (true) {
                    val header = readLine()
                    if (header.isEmpty()) break
                    if (header.lowercase().startsWith("content-length:")) length = header.substringAfter(':').trim().toInt()
                }
                input.readFully(ByteArray(length))
            }
            val status = readLine()
            assertTrue(status.startsWith("HTTP/1.1 101"), "upgrade was refused: $status")
            while (readLine().isNotEmpty()) { /* skip the response headers */ }
        }

        private fun readLine(): String {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c == -1) throw EOFException()
                if (c == '\n'.code) return line.toString().trimEnd('\r')
                line.append(c.toChar())
            }
        }

        /** Writes one masked frame. */
        fun writeFrame(opcode: Int, payload: ByteArray, fin: Boolean = true) = writeRaw(clientFrame(opcode, payload, fin))

        /** Writes bytes as they are, in one write: several frames written this way arrive together. */
        fun writeRaw(bytes: ByteArray) {
            output.write(bytes)
            output.flush()
        }

        /** Reads one frame as (opcode, payload). */
        fun readFrame(): Pair<Int, ByteArray> {
            val opcode = input.readUnsignedByte() and 0x0F
            val length = when (val short = input.readUnsignedByte() and 0x7F) {
                126 -> input.readUnsignedShort().toLong()
                127 -> input.readLong()
                else -> short.toLong()
            }
            return opcode to ByteArray(length.toInt()).also { input.readFully(it) }
        }

        override fun close() = socket.close()
    }

    private companion object {
        const val CONTINUATION = 0x0
        const val TEXT = 0x1
        const val BINARY = 0x2
        const val CLOSE = 0x8
        const val PING = 0x9
    }

    @Test
    fun `a fragmented message arrives whole at a pub-sub handler`() {
        startEngine(forcePubSub = true)
        RawSocket("/echo").use { ws ->
            ws.writeFrame(TEXT, "Hel".toByteArray(), fin = false)
            ws.writeFrame(CONTINUATION, "lo, ".toByteArray(), fin = false)
            ws.writeFrame(CONTINUATION, "world".toByteArray(), fin = true)

            val (opcode, payload) = ws.readFrame()
            assertEquals(TEXT, opcode)
            assertEquals("Hello, world", payload.decodeToString())
        }
    }

    @Test
    fun `a client close is answered with a clean close`() {
        startEngine(forcePubSub = true)
        val closed = CompletableFuture<Int>()
        // onClosed is only called once the server's own close frame arrives; a dropped connection is a failure.
        val ws = okHttp.value.openWebSocket(
            "ws://127.0.0.1:$port/echo",
            onClosed = { closed.complete(it) },
            onFailure = { closed.completeExceptionally(it) },
        )
        ws.close(1000, "done")

        assertEquals(1000, closed.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `the server pings and drops a socket that never answers`() {
        startEngine(
            forcePubSub = true,
            webSocket = WebSocketSettings(ping = 200.milliseconds, pongTimeout = 300.milliseconds),
        )
        RawSocket("/echo").use { ws ->
            val (opcode, _) = ws.readFrame()
            assertEquals(PING, opcode, "expected the first thing a quiet socket hears to be a ping")

            // Never answer. The server should give up on us well inside the read timeout.
            val ended = try {
                while (true) ws.readFrame()
                @Suppress("UNREACHABLE_CODE") false
            } catch (_: EOFException) {
                true
            } catch (_: SocketException) {
                true
            } catch (_: SocketTimeoutException) {
                false
            }
            assertTrue(ended, "a socket that ignored every ping was left open")
        }
    }

    @Test
    fun `a quiet socket that answers pings stays open`() {
        startEngine(
            forcePubSub = true,
            webSocket = WebSocketSettings(ping = 100.milliseconds, pongTimeout = 200.milliseconds),
        )
        val echoed = CompletableFuture<String>()
        // OkHttp answers pings on its own, as browsers do.
        val ws = okHttp.value.openWebSocket(
            "ws://127.0.0.1:$port/echo",
            onText = { echoed.complete(it) },
            onFailure = { echoed.completeExceptionally(it) },
        )

        // Several times the window in which an unanswered ping would have closed it.
        Thread.sleep(1_500)
        assertTrue(ws.send("still here"))

        assertEquals("still here", echoed.get(5, TimeUnit.SECONDS))
        ws.cancel()
    }

    @Test
    fun `sending to a client that is not reading suspends the sender`() {
        // Pings off: this client deliberately reads nothing, and must not be dropped for it mid-test.
        startEngine(webSocket = WebSocketSettings(ping = null))
        RawSocket("/flood").use {
            // Wait for the count to stop moving: the socket buffers are full and the sender is parked.
            var last = -1
            var stableSince = System.currentTimeMillis()
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                val now = floodSent.get()
                if (now != last) {
                    last = now
                    stableSince = System.currentTimeMillis()
                } else if (now > 0 && System.currentTimeMillis() - stableSince > 1_000) break
                Thread.sleep(50)
            }

            val megabytes = FLOOD_FRAMES.toLong() * FLOOD_FRAME_BYTES / (1024 * 1024)
            assertTrue(
                last < FLOOD_FRAMES / 2,
                "the handler pushed $last of $FLOOD_FRAMES frames ($megabytes MiB in all) at a client " +
                        "reading none of them, so they are being buffered in memory",
            )
        }
    }

    @Test
    fun `a client that sends but never reads is dropped`() {
        startEngine(
            forcePubSub = true,
            webSocket = WebSocketSettings(ping = 100.milliseconds, pongTimeout = 200.milliseconds),
        )
        RawSocket("/echo").use { ws ->
            // Every frame is echoed at a client that reads none of it. Once the echoes have nowhere
            // to go the handler stalls and the server stops reading, so this write blocks until
            // the server gives up on the socket.
            val frame = clientFrame(BINARY, ByteArray(0xFFFF))
            val writing = CompletableFuture.supplyAsync {
                try {
                    repeat(4_000) { ws.writeRaw(frame) }
                    false
                } catch (_: java.io.IOException) {
                    true
                }
            }
            val dropped = try {
                writing.get(15, TimeUnit.SECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                false
            }
            assertTrue(dropped, "a client reading nothing was left connected, with the server's replies to it piled up")
        }
    }

    @Test
    fun `the suspend policy holds the peer back without losing or reordering frames`() {
        startEngine(
            reliability = suspendAfterOneFrame,
        )
        RawSocket("/slow").use { ws ->
            // All sent while the handler is still sleeping, into a buffer that holds one frame.
            repeat(SLOW_FRAMES) { ws.writeFrame(TEXT, "frame-$it".toByteArray()) }

            awaitUntil(10.seconds) { slowReceived.size == SLOW_FRAMES }
            assertEquals((0 until SLOW_FRAMES).map { "frame-$it" }, slowReceived.toList())
        }
    }

    @Test
    fun `a malformed request is answered with 400`() {
        startEngine()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write("this is not http\r\n\r\n".toByteArray())
                flush()
            }
            val statusLine = socket.getInputStream().bufferedReader().readLine()
            assertTrue(statusLine.contains(" 400 "), "expected a 400, got: $statusLine")
        }
    }

    private fun `an upgrade without a key is refused`(forcePubSub: Boolean, path: String) {
        startEngine(forcePubSub = forcePubSub)
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(
                    ("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Version: 13\r\n\r\n").toByteArray()
                )
                flush()
            }
            // Used to go unanswered on the pub/sub path, holding the connection until the idle timeout.
            val statusLine = socket.getInputStream().bufferedReader().readLine()
            assertTrue(statusLine?.contains(" 400 ") == true, "expected a 400, got: $statusLine")
        }
    }

    @Test
    fun `an upgrade without a key is refused at a pub-sub handler`() =
        `an upgrade without a key is refused`(forcePubSub = true, path = "/echo")

    @Test
    fun `an upgrade without a key is refused at a direct handler`() =
        `an upgrade without a key is refused`(forcePubSub = false, path = "/direct-echo")

    @Test
    fun `an upgrade asking for an unsupported version is refused and closed`() {
        startEngine()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().apply {
                write(
                    ("GET /direct-echo HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 12\r\n\r\n").toByteArray()
                )
                flush()
            }
            val reader = socket.getInputStream().bufferedReader()
            val statusLine = reader.readLine()
            assertTrue(statusLine?.contains(" 426 ") == true, "expected a 426, got: $statusLine")
            val closed = try {
                reader.readText()
                true
            } catch (_: SocketTimeoutException) {
                false
            }
            assertTrue(closed, "the refused connection was left open")
        }
    }

    @Test
    fun `an upgrade sent right behind another request still connects`() {
        startEngine()
        // The upgrade waits behind /hello and is taken up from the queue once /hello is answered.
        RawSocket("/direct-echo", pipelinedBefore = "/hello").use { ws ->
            ws.writeFrame(TEXT, "after the queue".toByteArray())
            val (opcode, payload) = ws.readFrame()
            assertEquals(TEXT, opcode)
            assertEquals("after the queue", payload.decodeToString())
        }
    }

    @Test
    fun `a compressed frame that inflates past the size limit closes the socket`() {
        startEngine(
            forcePubSub = true,
            compression = true,
            webSocket = WebSocketSettings(ping = null, maxFrameSize = 64.kibibytes),
        )
        RawSocket("/echo", extraHeaders = "Sec-WebSocket-Extensions: permessage-deflate\r\n").use { ws ->
            // A few KiB on the wire, 1 MiB once inflated: 16 times the limit.
            ws.writeRaw(clientFrame(BINARY, deflateBomb(1024 * 1024), rsv1 = true))

            val (opcode, payload) = try {
                ws.readFrame()
            } catch (_: EOFException) {
                CLOSE to ByteArray(0)
            } catch (_: SocketException) {
                CLOSE to ByteArray(0)
            }
            assertEquals(CLOSE, opcode, "the inflated message was accepted and echoed instead of refused")
            if (payload.size >= 2) {
                val code = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                assertEquals(1009, code)
            }
        }
    }

    @Test
    fun `a socket held back by a slow handler is not dropped for unread pongs`() {
        startEngine(
            // The handler sleeps a second before reading: over three times the window for an unanswered ping.
            webSocket = WebSocketSettings(ping = 100.milliseconds, pongTimeout = 200.milliseconds),
            reliability = suspendAfterOneFrame,
        )
        val failure = CompletableFuture<Throwable>()
        // OkHttp answers every ping. The server just is not reading while the handler is behind.
        val ws = okHttp.value.openWebSocket("ws://127.0.0.1:$port/slow", onFailure = { failure.complete(it) })
        repeat(SLOW_FRAMES) { ws.send("frame-$it") }

        awaitUntil(10.seconds) { slowReceived.size == SLOW_FRAMES || failure.isDone }
        assertEquals(
            (0 until SLOW_FRAMES).map { "frame-$it" },
            slowReceived.toList(),
            "socket failure: ${failure.getNow(null)}",
        )
        ws.cancel()
    }

    @Test
    fun `frames sent just before a close are all delivered`() {
        startEngine(
            reliability = suspendAfterOneFrame,
        )
        RawSocket("/slow").use { ws ->
            // One write, so the close is read along with the frames: all but one of them are still
            // waiting for room when it arrives and the connection closes.
            val frames = ByteArrayOutputStream()
            repeat(CLOSE_RACE_FRAMES) { frames.write(clientFrame(TEXT, "frame-$it".toByteArray())) }
            frames.write(clientFrame(CLOSE, byteArrayOf(0x03, 0xE8.toByte())))
            ws.writeRaw(frames.toByteArray())

            awaitUntil(10.seconds) { slowReceived.size == CLOSE_RACE_FRAMES }
            assertEquals((0 until CLOSE_RACE_FRAMES).map { "frame-$it" }, slowReceived.toList())
        }
    }

    private fun awaitUntil(timeout: Duration, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(25)
    }
}
