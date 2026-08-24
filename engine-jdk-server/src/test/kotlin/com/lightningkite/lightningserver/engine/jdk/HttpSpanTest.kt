package com.lightningkite.lightningserver.engine.jdk

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.engine.local.enginePubSub
import com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.otel.OtelTelemetryBackend
import com.lightningkite.services.telemetry.TelemetryBackend
import io.opentelemetry.api.trace.SpanId
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
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
 * Verifies that the JDK engine produces a root HTTP span with verb + path pattern.
 *
 * JdkEngine.start() calls the shared `ServerRuntime.handle(request)` extension, so this is
 * primarily an end-to-end smoke test through a real socket. The shared `handle()` is already
 * unit-tested in :core, so this only needs to confirm the wiring.
 */
class HttpSpanTest {

    private val exporter = InMemorySpanExporter.create()
    private val schemeName = "jdk-memory-${System.identityHashCode(this)}"

    init {
        // Register an in-memory OTel backend under a custom scheme so this test can read the spans
        // the engine produces. telemetrySettings now resolves a TelemetryBackend.Settings (not the
        // deprecated OpenTelemetrySettings), so the scheme registers on TelemetryBackend.Settings.
        TelemetryBackend.Settings.register(schemeName) { _, _, _ ->
            OtelTelemetryBackend(
                OpenTelemetrySdk.builder()
                    .setTracerProvider(
                        SdkTracerProvider.builder()
                            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                            .build()
                    )
                    .build()
            )
        }
    }

    object TestServer : ServerBuilder() {
        val getThing = path.path("things").arg<String>("id").get bind HttpHandler<PathSpec1<String>> {
            HttpResponse.plainText("thing ${it.path.arg1}")
        }
    }

    private lateinit var engine: JdkEngine
    private var port: Int = 0
    private lateinit var serverThread: Thread

    @AfterTest
    fun tearDown() {
        if (::serverThread.isInitialized) serverThread.interrupt()
    }

    @Test
    fun jdk_http_root_span_has_verb_and_route() {
        ServerSocket(0).use { port = (it.localSocketAddress as InetSocketAddress).port }
        engine = JdkEngine(TestServer.build())
        engine.settings.run {
            com.lightningkite.lightningserver.definition.generalSettings.useDefault()
            com.lightningkite.lightningserver.definition.secretBasis.useDefault()
            com.lightningkite.lightningserver.definition.loggingSettings.useDefault()
            telemetrySettings.set(TelemetryBackend.Settings(url = schemeName))
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
        // Wait for the JDK HttpServer to accept connections
        val deadline = System.currentTimeMillis() + 10_000
        var connected = false
        while (System.currentTimeMillis() < deadline) {
            startupError.get()?.let { fail("JdkEngine.start() threw: $it") }
            try {
                java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
                connected = true
                break
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        if (!connected) fail("JdkEngine never bound to 127.0.0.1:$port within 10s")

        val client = OkHttpClient()
        client.newCall(Request.Builder().url("http://127.0.0.1:$port/things/xyz").build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("thing xyz", resp.body!!.string())
        }
        client.dispatcher.executorService.shutdown()

        val spans = exporter.finishedSpanItems
        val root = spans.singleOrNull { it.parentSpanContext.spanId == SpanId.getInvalid() }
            ?: fail("Expected a single root span. Got: ${spans.map { it.name }}")
        assertEquals("lightningserver.GET /things/{id}", root.name, "JDK engine should produce a route-pattern root span")
    }
}
