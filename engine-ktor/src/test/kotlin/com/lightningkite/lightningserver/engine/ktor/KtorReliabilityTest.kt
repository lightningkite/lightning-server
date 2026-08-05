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
import io.ktor.client.*
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.cio.CIO as ServerCIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Real-port integration coverage for the Ktor engine's reliability guard rails: per-request timeout
 * (408) and max body size (413) with success at the limit. Uses the CIO server and client engines.
 */
class KtorReliabilityTest {

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

    private lateinit var engine: KtorEngine
    private var port: Int = 0
    private lateinit var serverThread: Thread
    private val maxBody = 1024L

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
                reliability = EngineReliabilitySettings(
                    maxBodySize = maxBody.bytes,
                ),
            )
        }
        serverThread = thread(start = true, isDaemon = true) { engine.start(ServerCIO) }
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
                return
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        fail("KtorEngine never bound within 15s")
    }

    private fun httpClient(): HttpClient = HttpClient(ClientCIO)

    @Test
    fun slow_handler_returns_503() = runBlocking {
        // A handler that exceeds its own timeout is a server-side failure to respond in time, which per RFC 7231 is
        // 503 Service Unavailable — NOT 408 (that means the *client* was too slow sending its request). The interceptor
        // maps the handler-timeout TimeoutCancellationException accordingly; KtorHttpConformanceTest pins the same.
        startServer()
        httpClient().use { client ->
            val resp = client.get("http://127.0.0.1:$port/slow")
            assertEquals(HttpStatusCode.ServiceUnavailable.value, resp.status.value)
        }
    }

    @Test
    fun oversized_body_returns_413() = runBlocking {
        startServer()
        httpClient().use { client ->
            val resp = client.post("http://127.0.0.1:$port/echo") {
                setBody(ByteArray((maxBody + 1).toInt()) { 'x'.code.toByte() })
            }
            assertEquals(HttpStatusCode.PayloadTooLarge.value, resp.status.value)
        }
    }

    @Test
    fun body_at_limit_succeeds() = runBlocking {
        startServer()
        httpClient().use { client ->
            val resp = client.post("http://127.0.0.1:$port/echo") {
                setBody(ByteArray(maxBody.toInt()) { 'x'.code.toByte() })
            }
            assertEquals(200, resp.status.value)
            assertEquals("received $maxBody", resp.bodyAsText())
        }
    }
}
