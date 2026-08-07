package com.lightningkite.lightningserver.engine.jdk

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
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Real-port integration coverage for the JDK engine's reliability guard rails: per-request timeout
 * (408), max body size (413) plus success at the limit, and the bounded-thread-pool concurrency model.
 *
 * These can only be exercised end-to-end over a socket because they depend on the JDK HttpServer's
 * read/timeout/thread-pool behavior, which [com.lightningkite.lightningserver.engine.local.LocalEngine]'s
 * in-process path bypasses.
 */
class JdkReliabilityTest {

    object TestServer : ServerBuilder() {
        // Required so the central 408 timeout error body can be serialized by DefaultExceptionHttpHandler.
        init { registerBasicMediaTypeCoders() }

        val slow = path.path("slow").get bind HttpHandler<PathSpec0>(timeout = 500.milliseconds) {
            delay(5.seconds) // longer than this handler's 500ms timeout
            HttpResponse.plainText("done")
        }
        val echo = path.path("echo").post bind HttpHandler<PathSpec0> { request ->
            val bytes = request.body?.data?.bytes() ?: ByteArray(0)
            HttpResponse.plainText("received ${bytes.size}")
        }
    }

    private lateinit var engine: JdkEngine
    private var port: Int = 0
    private lateinit var serverThread: Thread

    @AfterTest
    fun tearDown() {
        if (::engine.isInitialized) engine.shutdown()
        if (::serverThread.isInitialized) serverThread.interrupt()
    }

    private val maxBody = 1024L

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
            forceWebSocketPubSub.useDefault()
            com.lightningkite.lightningserver.websockets.websocketSettings.useDefault()
            jdkRunConfig set JdkRuntimeSettings(
                host = "127.0.0.1",
                port = port,
                reliability = EngineReliabilitySettings(
                    maxBodySize = maxBody.bytes,
                    workerThreads = 4,
                    shutdownDrainTimeout = 1.seconds, // keep tearDown fast
                ),
            )
        }
        serverThread = thread(start = true, isDaemon = true) { engine.start() }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
                return
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        fail("JdkEngine never bound within 10s")
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Test
    fun oversized_body_by_content_length_returns_413() {
        startServer()
        val body = ByteArray((maxBody + 1).toInt()) { 'x'.code.toByte() }.toRequestBody()
        client().newCall(Request.Builder().url("http://127.0.0.1:$port/echo").post(body).build()).execute()
            .use { resp ->
                assertEquals(HttpStatus.PayloadTooLarge.code, resp.code)
            }
    }

    @Test
    fun body_at_limit_succeeds() {
        startServer()
        val body = ByteArray(maxBody.toInt()) { 'x'.code.toByte() }.toRequestBody()
        client().newCall(Request.Builder().url("http://127.0.0.1:$port/echo").post(body).build()).execute()
            .use { resp ->
                assertEquals(200, resp.code)
                assertEquals("received $maxBody", resp.body.string())
            }
    }

    @Test
    fun concurrent_slow_requests_run_in_parallel() {
        // With a bounded pool of 4 threads and runBlocking-per-request, four 1s-handlers should
        // complete in roughly 1s wall-time, not 4s — proving requests are not serialized on a single
        // executor thread (the 2.6 fix).
        ServerSocket(0).use { port = (it.localSocketAddress as InetSocketAddress).port }
        engine = JdkEngine(ParallelServer.build())
        engine.settings.run {
            generalSettings.useDefault()
            secretBasis.useDefault()
            loggingSettings.useDefault()
            telemetrySettings.useDefault()
            enginePubSub.useDefault()
            engineCache.useDefault()
            forceWebSocketPubSub.useDefault()
            com.lightningkite.lightningserver.websockets.websocketSettings.useDefault()
            jdkRunConfig set JdkRuntimeSettings(
                host = "127.0.0.1",
                port = port,
                reliability = EngineReliabilitySettings(
                    workerThreads = 4,
                    shutdownDrainTimeout = 1.seconds,
                ),
            )
        }
        serverThread = thread(start = true, isDaemon = true) { engine.start() }
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100); }
                break
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }

        val client = client()
        val start = System.currentTimeMillis()
        val threads = (1..4).map {
            thread(start = true) {
                client.newCall(Request.Builder().url("http://127.0.0.1:$port/wait").build()).execute().use { resp ->
                    assertEquals(200, resp.code)
                }
            }
        }
        threads.forEach { it.join() }
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 3000, "4 concurrent 1s requests took ${elapsed}ms; expected ~1s (parallel)")
    }

    object ParallelServer : ServerBuilder() {
        val wait = path.path("wait").get bind HttpHandler<PathSpec0> {
            delay(1.seconds)
            HttpResponse.plainText("ok")
        }
    }
}
