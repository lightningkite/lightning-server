package com.lightningkite.lightningserver.engine.jdk

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.engine.local.enginePubSub
import com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import kotlinx.io.writeString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * End-to-end verification that the JDK engine correctly streams a
 * [com.lightningkite.services.data.Data.Sink] response body to the wire.
 *
 * Regression coverage for the same buffer-flush bug the Ktor engine had:
 * if the buffered `kotlinx.io.Sink` wrapping the `HttpExchange.responseBody`
 * is not closed, any bytes still in its 8 KB buffer never reach the client.
 * Small bodies vanish entirely; large bodies lose the trailing partial-segment.
 */
class JdkSinkResponseTest {

    object TestServer : ServerBuilder() {
        val sink = path.path("sink").get bind HttpHandler<PathSpec0> {
            HttpResponse(
                body = TypedData.sink(MediaType.Text.Plain) { out -> out.writeString("sink-content") },
                status = HttpStatus.OK,
            )
        }
        val bigSink = path.path("bigsink").get bind HttpHandler<PathSpec0> {
            val content = "x".repeat(100_000)
            HttpResponse(
                body = TypedData.sink(MediaType.Text.Plain) { out -> out.writeString(content) },
                status = HttpStatus.OK,
            )
        }
        val chunkedSink = path.path("chunkedsink").get bind HttpHandler<PathSpec0> {
            HttpResponse(
                body = TypedData.sink(MediaType.Application.OctetStream) { out ->
                    out.writeString("alpha")
                    out.writeString("|")
                    out.writeString("beta")
                    out.writeString("|")
                    out.writeString("gamma")
                },
                status = HttpStatus.OK,
            )
        }
    }

    private lateinit var engine: JdkEngine
    private var port: Int = 0
    private lateinit var serverThread: Thread

    @AfterTest
    fun tearDown() {
        if (::serverThread.isInitialized) serverThread.interrupt()
    }

    private fun startServer() {
        ServerSocket(0).use { port = (it.localSocketAddress as InetSocketAddress).port }
        engine = JdkEngine(TestServer.build())
        engine.settings.run {
            generalSettings.useDefault()
            secretBasis.useDefault()
            loggingSettings.useDefault()
            telemetrySettings.useDefault()
            enginePubSub.useDefault()
            engineCache.useDefault()
            com.lightningkite.lightningserver.websockets.webSocketSettings.useDefault()
            forceWebSocketPubSub.useDefault()
            jdkRunConfig set JdkRuntimeSettings(host = "127.0.0.1", port = port)
        }

        val startupError = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        serverThread = thread(start = true, isDaemon = true) {
            try {
                engine.start()
            } catch (t: Throwable) {
                startupError.set(t)
            }
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            startupError.get()?.let { fail("JdkEngine.start() threw: $it") }
            try {
                java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
                return
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        fail("JdkEngine never bound to 127.0.0.1:$port within 10s")
    }

    private fun client(): OkHttpClient = OkHttpClient()

    @Test
    fun small_sink_body_streams_full_content() {
        startServer()
        client().newCall(Request.Builder().url("http://127.0.0.1:$port/sink").build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("sink-content", resp.body.string())
        }
    }

    @Test
    fun large_sink_body_is_fully_flushed() {
        // The exact scenario that surfaced the bug in the Ktor engine: 100 KB payload
        // where the trailing bytes sit in the buffered sink at end-of-emit. Without a
        // close on the chain, only the first 96 KB (multiples of the 8 KB segment) reach
        // the client.
        startServer()
        client().newCall(Request.Builder().url("http://127.0.0.1:$port/bigsink").build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val text = resp.body.string()
            assertEquals(100_000, text.length)
            assertEquals("x".repeat(100_000), text)
        }
    }

    @Test
    fun chunked_sink_writes_preserve_order() {
        startServer()
        client().newCall(Request.Builder().url("http://127.0.0.1:$port/chunkedsink").build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("alpha|beta|gamma", resp.body.string())
        }
    }
}
