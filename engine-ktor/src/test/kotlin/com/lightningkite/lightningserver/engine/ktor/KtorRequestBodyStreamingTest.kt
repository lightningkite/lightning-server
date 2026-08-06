package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.local.EngineReliabilitySettings
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.engine.local.enginePubSub
import com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.data.DataSize.Companion.bytes
import io.ktor.server.cio.CIO as ServerCIO
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Reproduces the failure that motivated the cooperative (non-blocking) request-body redesign.
 *
 * The old path read the request body with a blocking `receiveStream()` + `runBlocking` **on the event-loop thread** —
 * the very thread responsible for feeding the body channel. A body that did not arrive all at once (slow client,
 * segmented upload, proxy) therefore parked the feeder thread while the handler waited for bytes that could never come:
 * a permanent deadlock. [slow_segmented_body_does_not_deadlock_event_loop] drives exactly that shape.
 *
 * It also pins [aborted_upload_is_not_accepted_as_complete] (finding A1): a client that dies mid-upload must surface as
 * an error, never as a clean — but truncated — body that the handler mistakes for the whole request.
 *
 * These run against a real CIO server on a real socket because the bug lives in the engine's thread/event-loop
 * handling; an in-memory `testApplication` would not exercise it.
 */
class KtorRequestBodyStreamingTest {

    object TestServer : ServerBuilder() {
        init { registerBasicMediaTypeCoders() }
        val echo = path.path("echo").post bind HttpHandler<PathSpec0> { request ->
            val bytes = request.body?.data?.bytes() ?: ByteArray(0)
            HttpResponse.plainText("received ${bytes.size}")
        }
    }

    private lateinit var engine: KtorEngine
    private var port: Int = 0
    private lateinit var serverThread: Thread
    private val maxBody = 4L * 1024 * 1024

    @AfterTest
    fun tearDown() {
        if (::serverThread.isInitialized) serverThread.interrupt()
    }

    private fun startServer() {
        ServerSocket(0).use { port = (it.localSocketAddress as InetSocketAddress).port }
        engine = KtorEngine(TestServer.build())
        engine.settings.run {
            generalSettings.useDefault()
            secretBasis.useDefault()
            loggingSettings.useDefault()
            telemetrySettings.useDefault()
            enginePubSub.useDefault()
            engineCache.useDefault()
            forceWebSocketPubSub.useDefault()
            ktorRunConfig set KtorRuntimeSettings(
                host = "127.0.0.1",
                port = port,
                reliability = EngineReliabilitySettings(maxBodySize = maxBody.bytes),
            )
        }
        serverThread = thread(start = true, isDaemon = true) { engine.start(ServerCIO) }
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
                return
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        fail("KtorEngine never bound within 15s")
    }

    /** Reads a full HTTP/1.1 response (headers + body) from a `Connection: close` socket, to EOF. */
    private fun Socket.readResponse(): String {
        val out = StringBuilder()
        getInputStream().bufferedReader().use { reader ->
            val buf = CharArray(4096)
            while (true) {
                val n = reader.read(buf)
                if (n == -1) break
                out.append(buf, 0, n)
            }
        }
        return out.toString()
    }

    @Test
    fun slow_segmented_body_does_not_deadlock_event_loop() {
        startServer()
        val segment = ByteArray(1024) { 'x'.code.toByte() }
        val segments = 64
        val total = segment.size * segments

        Socket("127.0.0.1", port).use { socket ->
            // If the old deadlock were present, the handler would never finish reading and this read would block
            // until the socket timeout, failing the test.
            socket.soTimeout = 15_000
            val out = socket.getOutputStream()
            out.write(
                ("POST /echo HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Content-Length: $total\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            out.flush()
            // Dribble the body out in segments so it is NOT all buffered before the handler starts reading —
            // this is the condition that deadlocked the blocking path.
            repeat(segments) {
                out.write(segment)
                out.flush()
                Thread.sleep(5)
            }

            val response = socket.readResponse()
            assertTrue(response.startsWith("HTTP/1.1 200"), "expected 200, got:\n$response")
            assertTrue(
                response.trimEnd().endsWith("received $total"),
                "handler should have read the whole segmented body ($total bytes); got:\n$response",
            )
        }
    }

    @Test
    fun aborted_upload_is_not_accepted_as_complete() {
        startServer()
        val declared = 20_000
        val sent = 8_000 // client dies after sending less than it promised

        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 15_000
            val out = socket.getOutputStream()
            out.write(
                ("POST /echo HTTP/1.1\r\n" +
                        "Host: 127.0.0.1\r\n" +
                        "Content-Type: application/octet-stream\r\n" +
                        "Content-Length: $declared\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
            )
            out.write(ByteArray(sent) { 'y'.code.toByte() })
            out.flush()
            // Half-close: signal end-of-input after only `sent` of `declared` bytes, then wait for the response.
            socket.shutdownOutput()

            val response = try {
                socket.readResponse()
            } catch (_: Exception) {
                "" // a dropped/reset connection is an acceptable "did not accept the truncated body" outcome
            }

            // A truncated upload must never be accepted as a successful request — neither as the full declared
            // length nor as a "complete" body of whatever fraction happened to arrive. Any 2xx here is silent data
            // loss; the correct outcome is an error status (400 truncated-body) or a dropped connection.
            assertTrue(
                !response.startsWith("HTTP/1.1 2"),
                "a truncated upload was accepted as a successful request (silent data loss); got:\n$response",
            )
        }
    }
}
