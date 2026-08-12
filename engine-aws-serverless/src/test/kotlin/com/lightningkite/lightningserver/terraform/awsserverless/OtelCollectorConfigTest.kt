package com.lightningkite.lightningserver.terraform.awsserverless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Lambda collector layer ignores the OTEL_* environment variables and falls back to exporting to
 * X-Ray unless it is handed a configuration file, so these check the file we hand it.
 */
class OtelCollectorConfigTest {

    @Test
    fun `grafana style config routes every signal to the otlp http exporter`() {
        val config = collectorConfig(
            endpoint = "https://otlp-gateway-prod-us-west-0.grafana.net/otlp",
            protocol = OtlpProtocol.HTTP,
            headerEnvVars = mapOf("Authorization" to "OTLP_HEADER_AUTHORIZATION"),
            enableTraces = true,
            enableMetrics = true,
        )
        assertEquals(
            $$$"""
            receivers:
              otlp:
                protocols:
                  grpc:
                    endpoint: localhost:4317
                  http:
                    endpoint: localhost:4318
            exporters:
              otlphttp:
                endpoint: https://otlp-gateway-prod-us-west-0.grafana.net/otlp
                headers:
                  Authorization: "$${env:OTLP_HEADER_AUTHORIZATION}"
            service:
              pipelines:
                traces:
                  receivers: [otlp]
                  exporters: [otlphttp]
                metrics:
                  receivers: [otlp]
                  exporters: [otlphttp]
                logs:
                  receivers: [otlp]
                  exporters: [otlphttp]
            """.trimIndent() + "\n",
            config
        )
    }

    /**
     * Terraform writes this file from a template, so a bare dollar-brace would be interpolated - and
     * fail - at plan time. The doubled dollar sign is Terraform's escape; the collector sees the
     * single form.
     */
    @Test
    fun `env references are escaped for terraform`() {
        val config = collectorConfig(
            endpoint = "https://api.honeycomb.io:443",
            protocol = OtlpProtocol.GRPC,
            headerEnvVars = mapOf("x-honeycomb-team" to "OTLP_HEADER_X_HONEYCOMB_TEAM"),
            enableTraces = true,
            enableMetrics = true,
        )
        assertTrue(config.contains($$$"""x-honeycomb-team: "$${env:OTLP_HEADER_X_HONEYCOMB_TEAM}""""))
        assertFalse(config.contains(Regex($$$"""(?<!\$)\$\{""")), "unescaped interpolation in $config")
    }

    @Test
    fun `grpc uses the otlp exporter`() {
        val config = collectorConfig(
            endpoint = "https://api.honeycomb.io:443",
            protocol = OtlpProtocol.GRPC,
            headerEnvVars = emptyMap(),
            enableTraces = true,
            enableMetrics = true,
        )
        assertTrue(config.contains("  otlp:\n    endpoint: https://api.honeycomb.io:443"))
        assertFalse(config.contains("otlphttp"))
        assertFalse(config.contains("headers:"))
    }

    @Test
    fun `disabled signals get no pipeline`() {
        val config = collectorConfig(
            endpoint = "https://example.com",
            protocol = OtlpProtocol.HTTP,
            headerEnvVars = emptyMap(),
            enableTraces = false,
            enableMetrics = false,
        )
        assertFalse(config.contains("    traces:"))
        assertFalse(config.contains("    metrics:"))
        assertTrue(config.contains("    logs:"))
    }
}
