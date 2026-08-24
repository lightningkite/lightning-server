package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.engine.local.engineCache
import com.lightningkite.lightningserver.engine.local.enginePubSub
import com.lightningkite.lightningserver.engine.local.forceWebSocketPubSub
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.otel.OtelTelemetryBackend
import com.lightningkite.services.telemetry.TelemetryBackend
import io.ktor.client.plugins.websocket.*
import io.ktor.server.testing.*
import io.ktor.websocket.*
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * Verifies that the Ktor engine's WebSocket lifecycle goes through the *WithMetrics wrappers,
 * producing spans for willConnect / didConnect / messageFromClient / disconnect.
 */
class WebSocketSpanTest {

    private val exporter = InMemorySpanExporter.create()

    private fun registerMemoryScheme() {
        // Register an in-memory OTel backend under a custom scheme so this test can read the spans
        // the engine produces. telemetrySettings now resolves a TelemetryBackend.Settings (not the
        // deprecated OpenTelemetrySettings), so the scheme registers on TelemetryBackend.Settings.
        TelemetryBackend.Settings.register("ws-memory-${System.identityHashCode(this)}") { _, _, _ ->
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

    private val schemeName = "ws-memory-${System.identityHashCode(this)}"

    object TestServer : ServerBuilder() {
        val mirror = path.path("mirror") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            messageFromClient = { frame -> send(frame) },
            disconnect = {},
        )
    }

    @Test
    fun ws_lifecycle_produces_metric_spans() {
        registerMemoryScheme()
        val engine = KtorEngine(TestServer.build())
        engine.settings.run {
            com.lightningkite.lightningserver.definition.generalSettings.useDefault()
            com.lightningkite.lightningserver.definition.secretBasis.useDefault()
            com.lightningkite.lightningserver.definition.loggingSettings.useDefault()
            telemetrySettings.set(TelemetryBackend.Settings(url = schemeName))
            enginePubSub.useDefault()
            engineCache.useDefault()
            com.lightningkite.lightningserver.websockets.webSocketSettings.useDefault()
            forceWebSocketPubSub set true  // hit the standard pub/sub branch where the *WithMetrics calls live
            ktorRunConfig set KtorRuntimeSettings(host = "127.0.0.1", port = 0)
        }
        engine.settings.readyUsingDefaults()

        testApplication {
            application { with(engine) { adapt() } }
            val client = createClient { install(WebSockets) }

            client.webSocket("/mirror?path=/mirror") {
                send(Frame.Text("hello"))
                val echoed = withTimeout(5_000.milliseconds) { incoming.receive() }
                assertTrue(echoed is Frame.Text)
            }
        }

        // Flush — SimpleSpanProcessor exports immediately, but disconnect can fire after testApplication
        // returns. Wait briefly for the disconnect span to land.
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
        if (missing.isNotEmpty()) {
            fail("Missing WS spans: $missing. Saw: $spanNames")
        }
    }
}
