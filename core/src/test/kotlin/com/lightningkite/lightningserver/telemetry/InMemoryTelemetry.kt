package com.lightningkite.lightningserver.telemetry

import com.lightningkite.services.otel.OpenTelemetrySettings
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor

/**
 * Test-only OTEL plumbing.
 *
 * Registers a "memory" URL scheme on [OpenTelemetrySettings] backed by [InMemorySpanExporter],
 * so tests can configure `telemetry { url = "memory" }` and then inspect the captured spans.
 *
 * Each `OpenTelemetrySdk` built via this scheme installs its own backing [InMemorySpanExporter];
 * use [latest] to access it. Tests that share a server runtime across cases should call
 * [latest].reset() between cases.
 */
internal object InMemoryTelemetry {
    @Volatile
    private var _latest: InMemorySpanExporter = InMemorySpanExporter.create()

    val latest: InMemorySpanExporter get() = _latest

    @Volatile
    private var latestMetrics: InMemoryMetricReader = InMemoryMetricReader.create()

    init {
        OpenTelemetrySettings.register("memory") { _, _, _ ->
            val exporter = InMemorySpanExporter.create()
            _latest = exporter
            val metricReader = InMemoryMetricReader.create()
            latestMetrics = metricReader
            OpenTelemetrySdk.builder()
                .setTracerProvider(
                    SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build()
                )
                .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metricReader).build())
                .build()
        }
    }

    fun finishedSpans(): List<SpanData> = _latest.finishedSpanItems.toList()

    fun metrics(): Collection<MetricData> = latestMetrics.collectAllMetrics()
}
