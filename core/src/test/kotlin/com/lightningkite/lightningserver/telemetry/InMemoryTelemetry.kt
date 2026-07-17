package com.lightningkite.lightningserver.telemetry

import com.lightningkite.services.otel.OtelTelemetryBackend
import com.lightningkite.services.telemetry.TelemetryBackend
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor

/**
 * Test-only telemetry plumbing.
 *
 * Registers a "memory" URL scheme on [TelemetryBackend.Settings] backed by [InMemorySpanExporter],
 * so tests can configure `telemetrySettings.set(TelemetryBackend.Settings(url = "memory"))` and
 * then inspect the captured spans via [finishedSpans].
 *
 * Each backend built via this scheme installs its own backing [InMemorySpanExporter];
 * use [latest] to access it. Tests that share a server runtime across cases should call
 * [latest].reset() between cases.
 */
internal object InMemoryTelemetry {
    @Volatile
    private var _latest: InMemorySpanExporter = InMemorySpanExporter.create()

    val latest: InMemorySpanExporter get() = _latest

    init {
        TelemetryBackend.Settings.register("memory") { _, _, _ ->
            val exporter = InMemorySpanExporter.create()
            _latest = exporter
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

    fun finishedSpans(): List<SpanData> = _latest.finishedSpanItems.toList()
}
