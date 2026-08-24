package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.engine.local.enginePubSub
import com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.otel.OtelTelemetryBackend
import com.lightningkite.services.telemetry.TelemetryBackend
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Verifies that the Netty engine's WebSocket lifecycle produces *WithMetrics spans
 * (willConnect / didConnect / messageFromClient / disconnect).
 */
class WebSocketSpanTest {

    private val exporter = InMemorySpanExporter.create()
    private val schemeName = "netty-ws-memory-${System.identityHashCode(this)}"

    private fun registerMemoryScheme() {
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
        val mirror = path.path("mirror") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            messageFromClient = { frame -> send(frame) },
            disconnect = {},
        )
    }

    private lateinit var engine: NettyEngine
    private var port: Int = 0
    private lateinit var client: OkHttpClient

    private fun startEngine() {
        ServerSocket(0).use { port = (it.localSocketAddress as InetSocketAddress).port }
        engine = NettyEngine(TestServer.build())
        engine.settings.run {
            com.lightningkite.lightningserver.definition.generalSettings.useDefault()
            com.lightningkite.lightningserver.definition.secretBasis.useDefault()
            com.lightningkite.lightningserver.definition.loggingSettings.useDefault()
            telemetrySettings.set(TelemetryBackend.Settings(url = schemeName))
            enginePubSub.useDefault()
            engineCache.useDefault()
            com.lightningkite.lightningserver.websockets.webSocketSettings.useDefault()
            forceWebSocketPubSub set true  // Use the pub/sub branch where *WithMetrics fires
            nettyRunConfig set NettyRuntimeSettings(host = "127.0.0.1", port = port)
        }
        Thread { engine.start() }.start()
        var tries = 0
        while (engine.boundAddress == null && tries < 50) {
            Thread.sleep(100); tries++
        }
        check(engine.boundAddress != null) { "NettyEngine failed to start within 5 seconds" }
        port = engine.boundAddress!!.port
        client = OkHttpClient()
    }

    @AfterTest
    fun tearDown() {
        if (::engine.isInitialized) engine.shutdown()
        if (::client.isInitialized) client.dispatcher.executorService.shutdown()
    }

    @Test
    fun ws_lifecycle_produces_metric_spans() {
        registerMemoryScheme()
        startEngine()

        val openLatch = CountDownLatch(1)
        val echoFuture = CompletableFuture<String>()

        val ws = client.newWebSocket(
            Request.Builder().url("ws://127.0.0.1:$port/mirror").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    openLatch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    echoFuture.complete(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    echoFuture.completeExceptionally(t)
                    openLatch.countDown()
                }
            }
        )

        assertTrue(openLatch.await(5, TimeUnit.SECONDS), "WebSocket failed to open")
        assertTrue(ws.send("ping"))
        val echoed = echoFuture.get(5, TimeUnit.SECONDS)
        check(echoed == "ping")
        ws.close(1000, "done")

        // Wait briefly for the disconnect span to land after socket close.
        val deadline = System.currentTimeMillis() + 2_000
        val expected = setOf("willConnect", "didConnect", "messageFromClient", "disconnect")
        while (System.currentTimeMillis() < deadline) {
            val seen = exporter.finishedSpanItems
                .mapNotNull { span -> expected.firstOrNull { span.name.contains(it) } }
                .toSet()
            if (seen == expected) break
            Thread.sleep(50)
        }

        val spanNames = exporter.finishedSpanItems.map { it.name }
        val missing = expected.filter { keyword -> spanNames.none { it.contains(keyword) } }
        if (missing.isNotEmpty()) fail("Missing Netty WS spans: $missing. Saw: $spanNames")
    }
}
