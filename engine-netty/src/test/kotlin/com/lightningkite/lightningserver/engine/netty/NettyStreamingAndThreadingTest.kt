package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.engine.local.BodyTooLargeException
import com.lightningkite.lightningserver.engine.local.EngineReliabilitySettings
import com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.webSocketSettings
import com.lightningkite.services.data.DataSize.Companion.bytes
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.delay
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.writeString
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val UPLOAD_BYTES = 200_000

/** Written by the `big-write` handler in a single call. */
private const val BIG_WRITE_BYTES = 1024 * 1024

/** Written by the `rows` handler, one small write each. */
private const val ROWS = 50_000

/** Counted down by the `count` handler when its first read of the body returns. */
@Volatile
private var uploadSeen = CountDownLatch(1)

/** Counted down by the test to let the streaming handlers produce the second half of their body. */
@Volatile
private var finishStream = CountDownLatch(1)

/** Counted down when the streamed body the `catches-limit` handler returned is closed. */
@Volatile
private var replacedBodyClosed = CountDownLatch(1)

private object StreamingTestServer : ServerBuilder() {
    init {
        registerBasicMediaTypeCoders()
    }

    val hello = path.path("hello").get bind HttpHandler<PathSpec0> { HttpResponse.plainText("hello") }
    val bye = path.path("bye").get bind HttpHandler<PathSpec0> { HttpResponse.plainText("bye") }

    // Reads the body a piece at a time, reporting as soon as the first piece is in hand.
    val count = path.path("count").post bind HttpHandler<PathSpec0> { request ->
        val source = request.body!!.data.suspendingSource()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            total += read
            buffer.clear()
            uploadSeen.countDown()
        }
        HttpResponse.plainText("received $total")
    }

    val slow = path.path("slow").get bind HttpHandler<PathSpec0> {
        delay(500)
        HttpResponse.plainText("slow")
    }

    // Swallows the body limit and answers with a streamed body anyway, which the engine has to discard.
    val catchesLimit = path.path("catches-limit").post bind HttpHandler<PathSpec0> { request ->
        try {
            request.body!!.data.bytes()
        } catch (_: BodyTooLargeException) {
        }
        val source = object : RawSource {
            override fun readAtMostTo(sink: Buffer, byteCount: Long): Long = -1L
            override fun close() = replacedBodyClosed.countDown()
        }
        HttpResponse(TypedData.source(source.buffered(), MediaType.Text.Plain))
    }

    // Takes its time and never looks at its body.
    val unread = path.path("unread").post bind HttpHandler<PathSpec0> {
        delay(300)
        HttpResponse.plainText("unread")
    }

    // Gives up on its body at once, then takes its time answering.
    val closesBody = path.path("closes-body").post bind HttpHandler<PathSpec0> { request ->
        request.body!!.data.close()
        delay(3_000)
        HttpResponse.plainText("done")
    }

    // Leaves its body unread until the buffer for it has filled, then gives up on it and waits to be let go.
    val closesBodyLate = path.path("closes-body-late").post bind HttpHandler<PathSpec0> { request ->
        delay(500)
        request.body!!.data.close()
        while (finishStream.count > 0) delay(20)
        HttpResponse.plainText("done")
    }

    // A blocking body that hands over a whole megabyte in one write.
    val bigWrite = path.path("big-write").get bind HttpHandler<PathSpec0> {
        HttpResponse(TypedData.sink(MediaType.Application.OctetStream) { sink ->
            sink.write(ByteArray(BIG_WRITE_BYTES) { it.toByte() })
        })
    }

    // A body written a short row at a time, as an export would be.
    val rows = path.path("rows").get bind HttpHandler<PathSpec0> {
        HttpResponse(TypedData.suspendingSink(MediaType.Text.Plain) { sink ->
            repeat(ROWS) { sink.write(Buffer().apply { writeString("row-$it\n") }) }
        })
    }

    val stream = path.path("stream").get bind HttpHandler<PathSpec0> {
        HttpResponse(TypedData.suspendingSink(MediaType.Text.Plain) { sink ->
            sink.write(Buffer().apply { writeString("first\n") })
            while (finishStream.count > 0) delay(20)
            sink.write(Buffer().apply { writeString("second\n") })
        })
    }

    val blockingStream = path.path("blocking-stream").get bind HttpHandler<PathSpec0> {
        HttpResponse(TypedData.sink(MediaType.Text.Plain) { sink ->
            sink.writeString("first\n")
            sink.flush()
            finishStream.await(10, TimeUnit.SECONDS)
            sink.writeString("second\n")
        })
    }

    // Blocks its thread outright, the way a careless handler might.
    val block = path.path("block").get bind HttpHandler<PathSpec0> {
        Thread.sleep(2_000)
        HttpResponse.plainText("unblocked")
    }

    val ordered = path.path("ordered") bind WebSocketHandler(
        storageSerializer = Unit.serializer(),
        willConnect = { Unit },
        messageFromClient = { frame ->
            if (frame is WebSocketFrame.Text && frame.content == "slow") delay(300)
            send(frame)
        },
    )
}

/**
 * Bodies are streamed in both directions, and application code runs off Netty's event loops.
 *
 * Each test here is shaped so that the old behavior fails it: a body buffered whole before the
 * handler runs, a response buffered whole before any of it is sent, a handler that blocks the one
 * event loop every connection shares, and socket messages handled concurrently.
 */
class NettyStreamingAndThreadingTest {

    private lateinit var engine: NettyEngine
    private var port: Int = 0
    private val okHttp = lazy { OkHttpClient() }
    private val http: HttpClient = HttpClient.newHttpClient()

    private fun startEngine(
        maxBodySize: Long = 16L * 1024 * 1024,
        workerThreads: Int? = null,
        compression: Boolean = false,
        disableResponseStreaming: Boolean = false,
        idleTimeout: Duration = EngineReliabilitySettings().idleTimeout,
    ) {
        uploadSeen = CountDownLatch(1)
        finishStream = CountDownLatch(1)
        replacedBodyClosed = CountDownLatch(1)
        engine = NettyEngine(StreamingTestServer.build(), disableResponseStreaming = { disableResponseStreaming })
        engine.useFrameworkDefaults()
        engine.settings.run {
            webSocketSettings.useDefault()
            forceWebSocketPubSub set true
            nettyRunConfig set NettyRuntimeSettings(
                host = "127.0.0.1",
                port = 0,
                workerThreads = workerThreads,
                webSocketCompression = compression,
                reliability = EngineReliabilitySettings(
                    maxBodySize = maxBodySize.bytes,
                    idleTimeout = idleTimeout,
                    shutdownDrainTimeout = 1.seconds,
                ),
            )
        }
        port = engine.startInBackground()
    }

    @AfterTest
    fun tearDown() {
        finishStream.countDown()
        if (::engine.isInitialized) engine.shutdown()
        if (okHttp.isInitialized()) okHttp.value.dispatcher.executorService.shutdown()
    }

    private fun get(path: String) = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).GET().build()

    /** A POST of 50,000 bytes with no declared length, so it goes out chunked with no Content-Length to reject up front. */
    private fun postOfUndeclaredLength(path: String) = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
        .POST(HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(ByteArray(50_000)) })
        .build()

    /** Writes [request] to this socket as it is. */
    private fun Socket.send(request: String) {
        getOutputStream().run {
            write(request.toByteArray())
            flush()
        }
    }

    /** Reads from [socket] until [done] is satisfied by everything read so far, or nothing more comes. */
    private fun readUntil(socket: Socket, done: (String) -> Boolean): String {
        socket.soTimeout = 5_000
        val text = StringBuilder()
        val chunk = ByteArray(8192)
        try {
            while (!done(text.toString())) {
                val read = socket.getInputStream().read(chunk)
                if (read < 0) break
                text.append(String(chunk, 0, read))
            }
        } catch (_: SocketTimeoutException) {
        }
        return text.toString()
    }

    @Test
    fun `a handler starts reading an upload before all of it has arrived`() {
        startEngine()
        Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            out.write(
                "POST /count HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/octet-stream\r\nContent-Length: $UPLOAD_BYTES\r\n\r\n".toByteArray()
            )
            out.write(ByteArray(1_000))
            out.flush()

            assertTrue(
                uploadSeen.await(5, TimeUnit.SECONDS),
                "the handler was given nothing until the whole body had been received",
            )

            out.write(ByteArray(UPLOAD_BYTES - 1_000))
            out.flush()
            val response = readUntil(socket) { it.contains("received ") && it.endsWith("$UPLOAD_BYTES") }
            assertTrue(response.startsWith("HTTP/1.1 200"), response)
            assertTrue(response.endsWith("received $UPLOAD_BYTES"), response)
        }
    }

    @Test
    fun `an upload of undeclared length is refused once it passes the limit`() {
        startEngine(maxBodySize = 1024)
        val response = http.send(postOfUndeclaredLength("/count"), BodyHandlers.ofString())
        assertEquals(413, response.statusCode())
    }

    private fun `a response reaches the client while it is still being produced`(path: String) {
        startEngine()
        val response = http.send(get(path), BodyHandlers.ofInputStream())
        assertEquals(200, response.statusCode())
        response.body().bufferedReader().use { body ->
            val first = CompletableFuture.supplyAsync { body.readLine() }
            assertEquals(
                "first",
                first.get(5, TimeUnit.SECONDS),
                "the first half of the body should arrive while the handler is still holding back the second",
            )
            finishStream.countDown()
            assertEquals("second", body.readLine())
        }
    }

    @Test
    fun `a suspending response body is streamed`() =
        `a response reaches the client while it is still being produced`("/stream")

    @Test
    fun `a blocking response body is streamed`() =
        `a response reaches the client while it is still being produced`("/blocking-stream")

    @Test
    fun `a body written in many small pieces arrives whole and in order`() {
        startEngine()
        val response = http.send(get("/rows"), BodyHandlers.ofLines())
        assertEquals(200, response.statusCode())
        var expected = 0
        response.body().forEach { line -> assertEquals("row-${expected++}", line) }
        assertEquals(ROWS, expected)
    }

    @Test
    fun `a response is sent whole with its length when streaming is disabled`() {
        startEngine(disableResponseStreaming = true)
        finishStream.countDown()
        val response = http.send(get("/stream"), BodyHandlers.ofString())
        assertEquals("first\nsecond\n", response.body())
        assertEquals("13", response.headers().firstValue("Content-Length").orElse(null))
    }

    @Test
    fun `a handler that blocks its thread does not stall other connections`() {
        // One event loop for every connection: if handlers ran on it, the request below would wait out the block.
        startEngine(workerThreads = 1)
        val blocked = http.sendAsync(get("/block"), BodyHandlers.ofString())
        Thread.sleep(300)

        val started = System.currentTimeMillis()
        val response = HttpClient.newHttpClient().send(get("/hello"), BodyHandlers.ofString())
        val took = System.currentTimeMillis() - started

        assertEquals("hello", response.body())
        assertTrue(took < 1_000, "a request on another connection took ${took}ms behind a blocking handler")
        assertEquals("unblocked", blocked.get(10, TimeUnit.SECONDS).body())
    }

    @Test
    fun `requests sent back to back on one connection are answered in order`() {
        startEngine()
        Socket("127.0.0.1", port).use { socket ->
            // Both at once. The first is the slower of the two and leaves its body unread.
            socket.send(
                "POST /unread HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 5\r\n\r\nabcde" +
                        "GET /bye HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"
            )
            val responses = readUntil(socket) { it.endsWith("bye") }
            val first = responses.indexOf("unread")
            val second = responses.indexOf("bye")
            assertTrue(first >= 0 && second >= 0, "expected both responses, got: $responses")
            assertTrue(first < second, "responses came back out of order: $responses")
            assertEquals(2, Regex("HTTP/1\\.1 200").findAll(responses).count(), responses)
        }
    }

    @Test
    fun `many requests sent in one write are each answered`() {
        startEngine()
        Socket("127.0.0.1", port).use { socket ->
            // The decoder produces all of these from one read; each has to wait for the one before it.
            val requests = 50
            socket.send("GET /hello HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".repeat(requests))
            val answered = Regex("HTTP/1\\.1 200")
            val responses = readUntil(socket) { answered.findAll(it).count() == requests && it.endsWith("hello") }
            assertEquals(requests, answered.findAll(responses).count(), responses)
        }
    }

    @Test
    fun `a body sent as thousands of tiny chunks in one write is read whole`() {
        startEngine()
        Socket("127.0.0.1", port).use { socket ->
            // Each chunk reaches the engine as a message of its own, thousands of them from a single read.
            val chunks = 20_000
            socket.send(
                "POST /count HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\n\r\n" +
                        "1\r\na\r\n".repeat(chunks) + "0\r\n\r\n"
            )
            val response = readUntil(socket) { it.endsWith("received $chunks") }
            assertTrue(response.startsWith("HTTP/1.1 200"), response)
            assertTrue(response.endsWith("received $chunks"), response)
        }
    }

    /** Reads [socket] until the server closes it, failing if it is still open after five seconds. */
    private fun readUntilClosed(socket: Socket): String {
        socket.soTimeout = 5_000
        val text = StringBuilder()
        val chunk = ByteArray(8192)
        try {
            while (true) {
                val read = socket.getInputStream().read(chunk)
                if (read < 0) break
                text.append(String(chunk, 0, read))
            }
        } catch (_: SocketTimeoutException) {
            fail("the server left the connection open; received so far: $text")
        } catch (_: java.net.SocketException) {
            // Reset by the server, which is also closed.
        }
        return text.toString()
    }

    @Test
    fun `a request with a malformed query string is answered 400`() {
        startEngine()
        Socket("127.0.0.1", port).use { socket ->
            socket.send("GET /hello?q=%zz HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            val response = readUntilClosed(socket)
            assertTrue(response.startsWith("HTTP/1.1 400"), "expected a 400, got: $response")
        }
    }

    @Test
    fun `an upload refused at Expect 100-continue closes the connection`() {
        startEngine(maxBodySize = 1024)
        Socket("127.0.0.1", port).use { socket ->
            socket.send("POST /count HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 100000\r\nExpect: 100-continue\r\n\r\n")
            val response = readUntil(socket) { it.contains("\r\n\r\n") }
            assertTrue(response.startsWith("HTTP/1.1 413"), "expected a 413, got: $response")
            // Send the body anyway, as curl does once it stops waiting. The server should be gone.
            try {
                socket.getOutputStream().apply {
                    write(ByteArray(100_000))
                    flush()
                }
            } catch (_: java.io.IOException) {
            }
            readUntilClosed(socket)
        }
    }

    @Test
    fun `bodyless requests always keep the connection alive`() {
        startEngine()
        Socket("127.0.0.1", port).use { socket ->
            repeat(200) { i ->
                socket.send("GET /hello HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
                val response = readUntil(socket) { it.endsWith("hello") }
                assertTrue(response.startsWith("HTTP/1.1 200"), "request $i: $response")
                assertTrue(
                    response.lowercase().contains("connection: keep-alive"),
                    "request $i was answered without keep-alive: $response",
                )
            }
        }
    }

    @Test
    fun `shutdown lets a request in progress finish`() {
        startEngine()
        val inProgress = http.sendAsync(get("/slow"), BodyHandlers.ofString())
        Thread.sleep(150)
        val shutdown = Thread { engine.shutdown() }.apply { start() }

        val response = inProgress.get(5, TimeUnit.SECONDS)
        assertEquals(200, response.statusCode())
        assertEquals("slow", response.body())
        assertEquals("close", response.headers().firstValue("Connection").orElse(null)?.lowercase())
        shutdown.join(5_000)
    }

    @Test
    fun `a response replaced by 413 has its body closed`() {
        startEngine(maxBodySize = 1024)
        val response = http.send(postOfUndeclaredLength("/catches-limit"), BodyHandlers.ofString())
        assertEquals(413, response.statusCode())
        assertTrue(
            replacedBodyClosed.await(5, TimeUnit.SECONDS),
            "the handler's streamed body was thrown away without being closed",
        )
    }

    @Test
    fun `a handler slower than the idle timeout keeps its connection`() {
        // Nothing is read or written while /slow works, for over twice the idle timeout.
        startEngine(idleTimeout = 200.milliseconds)
        val response = http.send(get("/slow"), BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        assertEquals("slow", response.body())
    }

    @Test
    fun `an upload nobody is reading is eventually cut off`() {
        startEngine(maxBodySize = 1024)
        Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            out.write("POST /closes-body HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
            val chunk = ByteArray(8192)
            val chunkHeader = "2000\r\n".toByteArray()
            var sent = 0L
            val limit = 64L * 1024 * 1024
            val refused = try {
                // The handler is still asleep throughout, so only the engine can stop this.
                while (sent < limit) {
                    out.write(chunkHeader)
                    out.write(chunk)
                    out.write("\r\n".toByteArray())
                    sent += chunk.size
                }
                false
            } catch (_: java.io.IOException) {
                true
            }
            assertTrue(refused, "the server read and threw away ${sent / (1024 * 1024)} MiB that nobody was reading")
        }
    }

    @Test
    fun `an upload abandoned once its buffer is full is still taken in`() {
        val upload = 64L * 1024 * 1024
        startEngine(maxBodySize = 2 * upload)
        Socket("127.0.0.1", port).use { socket ->
            val out = socket.getOutputStream()
            out.write("POST /closes-body-late HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: $upload\r\n\r\n".toByteArray())
            // Far more than the socket buffers hold, so this only finishes if the server keeps
            // reading. The handler does not answer until told to, so nothing else restarts it.
            val sent = CompletableFuture.runAsync {
                val chunk = ByteArray(64 * 1024)
                repeat((upload / chunk.size).toInt()) { out.write(chunk) }
                out.flush()
            }
            try {
                sent.get(10, TimeUnit.SECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                fail("the upload stalled once the handler gave up on it")
            }
            finishStream.countDown()
            val response = readUntil(socket) { it.endsWith("done") }
            assertTrue(response.startsWith("HTTP/1.1 200"), response)
        }
    }

    @Test
    fun `a malformed length sent with Expect 100-continue is answered 400`() {
        startEngine()
        Socket("127.0.0.1", port).use { socket ->
            socket.send("POST /count HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: abc\r\nExpect: 100-continue\r\n\r\n")
            val response = readUntilClosed(socket)
            assertTrue(response.startsWith("HTTP/1.1 400"), "expected a 400, got: $response")
        }
    }

    @Test
    fun `a chunked body announced on a second Transfer-Encoding line is read`() {
        startEngine()
        Socket("127.0.0.1", port).use { socket ->
            socket.send(
                "POST /count HTTP/1.1\r\nHost: 127.0.0.1\r\nTransfer-Encoding: gzip\r\nTransfer-Encoding: chunked\r\n\r\n" +
                        "5\r\nabcde\r\n0\r\n\r\n"
            )
            val response = readUntil(socket) { it.endsWith("received 5") }
            assertTrue(response.startsWith("HTTP/1.1 200"), response)
            assertTrue(response.endsWith("received 5"), response)
        }
    }

    @Test
    fun `a large blocking write goes out in pieces`() {
        startEngine()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.send("GET /big-write HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            val input = java.io.DataInputStream(java.io.BufferedInputStream(socket.getInputStream()))
            fun readLine(): String {
                val line = StringBuilder()
                while (true) {
                    val c = input.read()
                    if (c == -1 || c == '\n'.code) return line.toString().trimEnd('\r')
                    line.append(c.toChar())
                }
            }
            val status = readLine()
            assertTrue(status.startsWith("HTTP/1.1 200"), status)
            val headers = generateSequence { readLine().takeIf { it.isNotEmpty() } }.toList()
            assertTrue(headers.any { it.equals("transfer-encoding: chunked", ignoreCase = true) }, "$headers")

            // Each chunk on the wire is one piece the engine wrote.
            val body = java.io.ByteArrayOutputStream()
            var largest = 0
            while (true) {
                val size = readLine().substringBefore(';').trim().toInt(16)
                if (size == 0) break
                largest = maxOf(largest, size)
                body.write(ByteArray(size).also { input.readFully(it) })
                readLine()
            }
            assertEquals(BIG_WRITE_BYTES, body.size())
            assertTrue(body.toByteArray().withIndex().all { (i, b) -> b == i.toByte() }, "the body arrived corrupted")
            assertTrue(largest <= 64 * 1024, "one write went out as a single $largest-byte piece")
        }
    }

    @Test
    fun `socket messages are handled one at a time in the order they arrived`() {
        startEngine()
        val echoed = CopyOnWriteArrayList<String>()
        val both = CountDownLatch(2)
        val ws = okHttp.value.openWebSocket(
            "ws://127.0.0.1:$port/ordered",
            onText = { echoed.add(it); both.countDown() },
        )

        ws.send("slow")
        ws.send("fast")

        assertTrue(both.await(5, TimeUnit.SECONDS), "expected two echoes, got $echoed")
        assertEquals(listOf("slow", "fast"), echoed.toList())
        ws.cancel()
    }

    @Test
    fun `a compressed socket still echoes`() {
        startEngine(compression = true)
        val echoed = CompletableFuture<String>()
        var extensions: String? = null
        val ws = okHttp.value.openWebSocket(
            "ws://127.0.0.1:$port/ordered",
            onHandshake = { extensions = it.header("Sec-WebSocket-Extensions") },
            onText = { echoed.complete(it) },
        )
        assertNotNull(extensions, "the server should have agreed to the compression OkHttp offers")

        // Long and repetitive, so it is actually sent compressed.
        val message = "compress me ".repeat(500)
        ws.send(message)

        assertEquals(message, echoed.get(5, TimeUnit.SECONDS))
        ws.cancel()
    }
}
