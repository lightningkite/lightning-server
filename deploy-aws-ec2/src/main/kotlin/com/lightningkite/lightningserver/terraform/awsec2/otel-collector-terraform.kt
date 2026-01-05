package com.lightningkite.lightningserver.terraform.awsec2

import com.lightningkite.services.Untested
import com.lightningkite.services.otel.OpenTelemetrySettings
import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.time.Duration.Companion.seconds

/**
 * OTLP protocol options for exporting telemetry data.
 */
public enum class OtlpProtocol(
    /** The protocol value for the OTEL_EXPORTER_OTLP_PROTOCOL environment variable */
    public val envValue: String,
    /** The default port for this protocol */
    public val defaultPort: Int,
    /** The URL scheme prefix for OpenTelemetrySettings */
    public val urlScheme: String,
) {
    /** gRPC protocol (default). More efficient but requires HTTP/2 support. */
    GRPC("grpc", 4317, "otlp-grpc"),
    /** HTTP with protobuf encoding. Use when gRPC isn't supported or going through HTTP-only proxies. */
    HTTP("http/protobuf", 4318, "otlp-http"),
}

/**
 * Configures OpenTelemetry for EC2 instances using the AWS Distro for OpenTelemetry (ADOT) Collector.
 *
 * This sets up the ADOT Collector as a systemd service that runs alongside your application,
 * collecting traces, metrics, and logs and forwarding them to an OTLP-compatible backend.
 *
 * ## Architecture
 *
 * ```
 * Application → ADOT Collector (localhost:4317) → Your OTLP Backend
 * ```
 *
 * The ADOT collector runs as a separate systemd service and receives telemetry via gRPC on localhost:4317.
 * It then exports to your configured backend (Honeycomb, Grafana Cloud, X-Ray, etc.).
 *
 * ## Tail-Based Sampling for Errors
 *
 * Unlike Lambda, EC2 has more flexibility for tail-based sampling. You can configure the
 * ADOT collector with a custom config that includes the `tail_sampling` processor.
 *
 * @param otlpEndpoint The OTLP endpoint to send telemetry to (e.g., "api.honeycomb.io:443").
 *                     If null, uses the default X-Ray exporter.
 * @param otlpProtocol The OTLP protocol to use: GRPC (default) or HTTP.
 * @param otlpHeaders Additional headers for OTLP export (e.g., API keys). Format: "key=value,key2=value2"
 * @param serviceName The service name to use in telemetry. Defaults to the project prefix.
 * @param samplingRatio Trace sampling ratio (0.0 to 1.0). Set to 0.01 for 1% sampling.
 * @param enableMetrics Whether to export metrics (default true).
 * @param enableTraces Whether to export traces (default true).
 * @param customCollectorConfig Optional custom collector configuration YAML.
 */
@Untested
context(emitter: TerraformAwsEc2Builder<*>)
public fun TerraformNeed<OpenTelemetrySettings?>.otelCollector(
    otlpEndpoint: String? = null,
    otlpProtocol: OtlpProtocol = OtlpProtocol.GRPC,
    otlpHeaders: String? = null,
    serviceName: String? = null,
    samplingRatio: Double? = null,
    enableMetrics: Boolean = true,
    enableTraces: Boolean = true,
    customCollectorConfig: String? = null,
): Unit {
    val effectiveServiceName = serviceName ?: emitter.projectPrefix

    // Add ADOT collector installation to user-data
    emitter.userDataScripts += """
        # === OpenTelemetry Collector Setup ===
        # Install AWS Distro for OpenTelemetry Collector
        rpm -ivh https://aws-otel-collector.s3.amazonaws.com/amazon_linux/arm64/latest/aws-otel-collector.rpm || \
        rpm -ivh https://aws-otel-collector.s3.amazonaws.com/amazon_linux/amd64/latest/aws-otel-collector.rpm

        systemctl enable aws-otel-collector
    """.trimIndent()

    // Add OTel environment variables to systemd
    emitter.systemdEnvironment["OTEL_SERVICE_NAME"] = effectiveServiceName
    emitter.systemdEnvironment["OTEL_RESOURCE_ATTRIBUTES"] = "service.name=$effectiveServiceName,deployment.environment=${emitter.projectPrefix}"
    emitter.systemdEnvironment["OTEL_PROPAGATORS"] = "tracecontext,baggage,xray"

    // Configure based on endpoint
    val usingXRay = otlpEndpoint == null

    if (otlpEndpoint != null) {
        val endpoint = if (otlpEndpoint.startsWith("http")) otlpEndpoint else "https://$otlpEndpoint"
        emitter.systemdEnvironment["OTEL_EXPORTER_OTLP_ENDPOINT"] = endpoint
        emitter.systemdEnvironment["OTEL_EXPORTER_OTLP_PROTOCOL"] = otlpProtocol.envValue

        if (otlpHeaders != null) {
            emitter.systemdEnvironment["OTEL_EXPORTER_OTLP_HEADERS"] = otlpHeaders
        }

        emitter.systemdEnvironment["OTEL_TRACES_EXPORTER"] = if (enableTraces) "otlp" else "none"
        emitter.systemdEnvironment["OTEL_METRICS_EXPORTER"] = if (enableMetrics) "otlp" else "none"
        emitter.systemdEnvironment["OTEL_LOGS_EXPORTER"] = "otlp"
    } else {
        // X-Ray exporter
        emitter.systemdEnvironment["OTEL_TRACES_EXPORTER"] = if (enableTraces) "xray" else "none"
        emitter.systemdEnvironment["OTEL_METRICS_EXPORTER"] = if (enableMetrics) "otlp" else "none"
        emitter.attachXRayPolicy = true
    }

    // Sampling configuration
    if (samplingRatio != null) {
        emitter.systemdEnvironment["OTEL_TRACES_SAMPLER"] = "parentbased_traceidratio"
        emitter.systemdEnvironment["OTEL_TRACES_SAMPLER_ARG"] = samplingRatio.toString()
    }

    // Custom collector config
    if (customCollectorConfig != null) {
        emitter.instanceFiles["/opt/aws/aws-otel-collector/etc/config.yaml"] = customCollectorConfig
        emitter.userDataScripts += """
            # Start ADOT collector with custom config
            systemctl start aws-otel-collector
        """.trimIndent()
    } else {
        // Generate default config based on endpoint
        val defaultConfig = generateDefaultCollectorConfig(
            otlpEndpoint = otlpEndpoint,
            otlpProtocol = otlpProtocol,
            enableTraces = enableTraces,
            enableMetrics = enableMetrics,
        )
        emitter.instanceFiles["/opt/aws/aws-otel-collector/etc/config.yaml"] = defaultConfig
        emitter.userDataScripts += """
            # Start ADOT collector
            systemctl start aws-otel-collector
        """.trimIndent()
    }

    // Configure the app's OpenTelemetry settings to use the local collector
    val localCollectorUrl = "${otlpProtocol.urlScheme}://localhost:${otlpProtocol.defaultPort}"
    emitter.fulfillSetting(name, Json.encodeToJsonElement(OpenTelemetrySettings(
        url = localCollectorUrl,
        batching = OpenTelemetrySettings.BatchingRules(
            frequency = 30.seconds,  // Longer batching for EC2 (always running)
            maxQueueSize = 2048,
            maxSize = 512,
            exportTimeout = 10.seconds
        ),
        sampling = samplingRatio?.let { OpenTelemetrySettings.Sampling(ratio = it, parentBased = true) }
    )))
}

private fun generateDefaultCollectorConfig(
    otlpEndpoint: String?,
    otlpProtocol: OtlpProtocol,
    enableTraces: Boolean,
    enableMetrics: Boolean,
): String {
    val exporters = if (otlpEndpoint != null) {
        """
exporters:
  otlp:
    endpoint: "${if (otlpEndpoint.startsWith("http")) otlpEndpoint else "https://$otlpEndpoint"}"
    headers:
      # Headers are passed via environment variables
        """.trimIndent()
    } else {
        """
exporters:
  awsxray:
  awsemf:
        """.trimIndent()
    }

    val tracesExporter = if (otlpEndpoint != null) "otlp" else "awsxray"
    val metricsExporter = if (otlpEndpoint != null) "otlp" else "awsemf"

    val pipelines = buildString {
        if (enableTraces) {
            appendLine("    traces:")
            appendLine("      receivers: [otlp]")
            appendLine("      processors: [batch]")
            appendLine("      exporters: [$tracesExporter]")
        }
        if (enableMetrics) {
            appendLine("    metrics:")
            appendLine("      receivers: [otlp]")
            appendLine("      processors: [batch]")
            appendLine("      exporters: [$metricsExporter]")
        }
    }

    return """
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

processors:
  batch:
    timeout: 10s
    send_batch_size: 512

$exporters

service:
  pipelines:
$pipelines
    """.trimIndent()
}

/**
 * Configures OpenTelemetry to export to Honeycomb via the ADOT collector.
 *
 * Honeycomb supports tail-based sampling via their Refinery product, which can be configured
 * to always keep traces with errors while sampling successful traces at a lower rate.
 *
 * @param dataset The Honeycomb dataset name (optional, defaults to service name).
 * @param samplingRatio Client-side sampling ratio (Honeycomb also supports server-side via Refinery).
 */
@Untested
context(emitter: TerraformAwsEc2Builder<*>)
public fun TerraformNeed<OpenTelemetrySettings?>.otelHoneycomb(
    dataset: String? = null,
    samplingRatio: Double? = null,
    otlpProtocol: OtlpProtocol = OtlpProtocol.GRPC,
): Unit {
    // Create a variable for the API key
    emitter.variable(object : TerraformNeed<String> {
        override val name: String = "honeycomb_api_key"
        override val serializer: KSerializer<String> = String.serializer()
        override val default: String? = null
        override val instructions: String = "Get an API key from Honeycomb: https://ui.honeycomb.io/account"
    })
    emitter.emit("variables") {
        "variable.honeycomb_api_key" {}
    }

    val headers = buildString {
        append("x-honeycomb-team=\${var.honeycomb_api_key}")
        if (dataset != null) {
            append(",x-honeycomb-dataset=$dataset")
        }
    }

    otelCollector(
        otlpEndpoint = "https://api.honeycomb.io:443",
        otlpProtocol = otlpProtocol,
        otlpHeaders = headers,
        samplingRatio = samplingRatio,
    )
}

/**
 * Configures OpenTelemetry to export to Grafana Cloud via the ADOT collector.
 *
 * @param instanceId Your Grafana Cloud instance ID.
 * @param zone The Grafana Cloud zone (e.g., "prod-us-east-0").
 * @param samplingRatio Client-side sampling ratio.
 */
@Untested
context(emitter: TerraformAwsEc2Builder<*>)
public fun TerraformNeed<OpenTelemetrySettings?>.otelGrafanaCloud(
    instanceId: String,
    zone: String = "prod-us-east-0",
    samplingRatio: Double? = null,
    otlpProtocol: OtlpProtocol = OtlpProtocol.GRPC,
): Unit {
    emitter.variable(object : TerraformNeed<String> {
        override val name: String = "grafana_cloud_api_key"
        override val serializer: KSerializer<String> = String.serializer()
        override val default: String? = null
        override val instructions: String = "Get an API key from Grafana Cloud: https://grafana.com/docs/grafana-cloud/account-management/authentication-and-permissions/access-policies/"
    })
    emitter.emit("variables") {
        "variable.grafana_cloud_api_key" {}
    }

    otelCollector(
        otlpEndpoint = "https://otlp-gateway-$zone.grafana.net/otlp",
        otlpProtocol = otlpProtocol,
        otlpHeaders = "Authorization=Basic \${base64encode(\"$instanceId:\${var.grafana_cloud_api_key}\")}",
        samplingRatio = samplingRatio,
    )
}

/**
 * Configures OpenTelemetry to export to AWS X-Ray (default, no external dependencies).
 *
 * X-Ray is the simplest option as it requires no additional infrastructure or API keys.
 * However, X-Ray has limited tail-based sampling support compared to dedicated observability platforms.
 *
 * This automatically:
 * - Attaches the X-Ray write access IAM policy to the EC2 instance role
 * - Configures ADOT collector to export to X-Ray
 *
 * @param samplingRatio Client-side sampling ratio (0.0 to 1.0).
 */
@Untested
context(emitter: TerraformAwsEc2Builder<*>)
public fun TerraformNeed<OpenTelemetrySettings?>.otelXRay(
    samplingRatio: Double? = null,
): Unit {
    otelCollector(
        otlpEndpoint = null,  // null means use X-Ray
        samplingRatio = samplingRatio,
    )
}

/**
 * Configures OpenTelemetry with a custom OTLP endpoint.
 *
 * Use this for self-hosted collectors, Jaeger, Tempo, or other OTLP-compatible backends.
 *
 * @param endpoint The OTLP endpoint URL (e.g., "http://collector.example.com:4317")
 * @param headers Optional authentication headers
 * @param samplingRatio Client-side sampling ratio
 */
@Untested
context(emitter: TerraformAwsEc2Builder<*>)
public fun TerraformNeed<OpenTelemetrySettings?>.otelCustomEndpoint(
    endpoint: String,
    headers: String? = null,
    samplingRatio: Double? = null,
    otlpProtocol: OtlpProtocol = OtlpProtocol.GRPC,
): Unit {
    otelCollector(
        otlpEndpoint = endpoint,
        otlpProtocol = otlpProtocol,
        otlpHeaders = headers,
        samplingRatio = samplingRatio,
    )
}
