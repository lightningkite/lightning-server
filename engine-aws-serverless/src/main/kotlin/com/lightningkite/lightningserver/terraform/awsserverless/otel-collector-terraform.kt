package com.lightningkite.lightningserver.terraform.awsserverless

import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.otel.OpenTelemetrySettings
import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import software.amazon.awssdk.services.lambda.model.Architecture
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
 * Environment variables the ADOT Collector Lambda layer reads to locate its configuration file.
 * Without one of them the layer falls back to its built-in configuration, which exports traces to
 * AWS X-Ray. `..._URI` is the current name
 * (https://github.com/open-telemetry/opentelemetry-lambda/blob/main/collector/README.md); `..._FILE`
 * is the older name that layers in the field still honor. Both get the same value, so whichever one
 * a given layer build reads is correct.
 */
private val COLLECTOR_CONFIG_VARS = listOf(
    "OPENTELEMETRY_COLLECTOR_CONFIG_URI",
    "OPENTELEMETRY_COLLECTOR_CONFIG_FILE",
)

/** Name of the generated collector config within the Lambda package, and where the layer finds it. */
private const val COLLECTOR_CONFIG_FILE = "collector.yaml"
private const val COLLECTOR_CONFIG_PATH = "/var/task/$COLLECTOR_CONFIG_FILE"

/**
 * Configures OpenTelemetry for AWS Lambda using the AWS Distro for OpenTelemetry (ADOT) Collector layer.
 *
 * This sets up the ADOT Collector Lambda extension which runs as a sidecar process in your Lambda
 * function, collecting traces, metrics, and logs and forwarding them to an OTLP-compatible backend.
 *
 * **Note:** This uses the collector-only layer (no auto-instrumentation). Your application must
 * manually instrument with the OpenTelemetry SDK. Lightning Server provides built-in instrumentation.
 *
 * ## Architecture
 *
 * ```
 * Lambda Function → ADOT Collector (localhost:4317) → Your OTLP Backend
 * ```
 *
 * The ADOT collector runs as a Lambda extension and receives telemetry via gRPC on localhost:4317.
 * It then exports to your configured backend (Honeycomb, Grafana Cloud, X-Ray, etc.).
 *
 * ## Tail-Based Sampling for Errors
 *
 * To achieve tail-based sampling (low % baseline + always capture errors), you need an external
 * OpenTelemetry Collector with the `tail_sampling` processor. The ADOT Lambda layer only supports
 * head-based sampling. Options:
 *
 * 1. **External Collector**: Deploy an OTel Collector (ECS/EC2) with tail_sampling processor,
 *    have Lambda export to it, then forward to your backend.
 *
 * 2. **Backend-side sampling**: Some backends (Honeycomb, Grafana Tempo) support refinery/tail
 *    sampling on ingestion.
 *
 * 3. **Head-based with high error bias**: Use ADOT's built-in sampling but set a higher ratio,
 *    accepting the cost trade-off.
 *
 * @param collectorLayerVersion The ADOT collector layer version string (e.g., "0-117-0" for v0.117.0).
 *                              Check https://github.com/aws-observability/aws-otel-lambda for the latest.
 * @param layerVersion The layer version number (typically 1 for new versions).
 * @param otlpEndpoint The OTLP endpoint the collector forwards to (e.g., "api.honeycomb.io:443").
 *                     If null, uses the default X-Ray exporter.
 * @param otlpProtocol The protocol the collector uses to reach [otlpEndpoint]: GRPC (default) or HTTP.
 *                     Use HTTP when your backend doesn't support gRPC (Grafana Cloud's OTLP gateway,
 *                     for instance) or when going through HTTP-only proxies. The application always
 *                     reaches the local collector over gRPC.
 * @param otlpHeaders Headers for OTLP export, typically credentials. Values may be Terraform
 *                    expressions; they are passed as Lambda environment variables and referenced from
 *                    the collector config, so they never land in the deployment package.
 * @param serviceName The service name to use in telemetry. Defaults to the handler class name.
 * @param samplingRatio Trace sampling ratio (0.0 to 1.0). Set to 0.01 for 1% sampling. Only applies
 *                      to head-based sampling. For tail-based sampling with error capture, use an
 *                      external collector.
 * @param enableMetrics Whether to export metrics (default true).
 * @param enableTraces Whether to export traces (default true).
 * @param enableLambdaTracing Whether to enable Lambda's built-in X-Ray tracing (tracing_config).
 *                            null = auto (true for X-Ray backend, false for OTLP endpoints).
 *                            Set to true for X-Ray integration, false to avoid duplicate traces with external backends.
 * @param customCollectorConfig Optional collector configuration YAML, replacing the generated one.
 *                              It is written through a Terraform template, so any literal dollar-brace
 *                              sequence in it (such as the collector's own `env:` references) must be
 *                              escaped by doubling the dollar sign.
 */
context(emitter: TerraformAwsServerlessBuilder<*>)
public fun TerraformNeed<TelemetryBackend.Settings>.otelCollector(
    collectorLayerVersion: String = "0-117-0",
    layerVersion: Int = 1,
    otlpEndpoint: String? = null,
    otlpProtocol: OtlpProtocol = OtlpProtocol.GRPC,
    otlpHeaders: Map<String, String> = emptyMap(),
    serviceName: String? = null,
    samplingRatio: Double? = null,
    enableMetrics: Boolean = true,
    enableTraces: Boolean = true,
    enableLambdaTracing: Boolean? = null,
    customCollectorConfig: String? = null,
): Unit {
    val arch = if (emitter.architecture == Architecture.ARM64) "arm64" else "amd64"

    // Add the ADOT collector-only layer (NO auto-instrumentation)
    // ARN format: arn:aws:lambda:<region>:901920570463:layer:aws-otel-collector-<arch>-ver-<version>:<layer-version>
    // See: https://github.com/aws-observability/aws-otel-lambda
    emitter.lambdaLayers += "arn:aws:lambda:${emitter.region.id()}:901920570463:layer:aws-otel-collector-$arch-ver-$collectorLayerVersion:$layerVersion"

    // Service identification
    val effectiveServiceName = serviceName ?: emitter.handler.qualifiedName ?: emitter.projectPrefix
    emitter.lambdaEnvironment["OTEL_SERVICE_NAME"] = effectiveServiceName
    emitter.lambdaEnvironment["OTEL_RESOURCE_ATTRIBUTES"] =
        "service.name=$effectiveServiceName,deployment.environment=${emitter.projectPrefix}"

    // Determine if using X-Ray (no custom endpoint)
    val usingXRay = otlpEndpoint == null

    // Where the collector sends what it receives. This has to be expressed in the collector's own
    // configuration file: the OTEL_* environment variables configure OpenTelemetry SDKs, and the
    // collector reads none of them. Without a configuration file the layer uses its built-in one,
    // which exports traces to X-Ray no matter what those variables say.
    if (otlpEndpoint != null) {
        // Header values are credentials, so they ride in Lambda environment variables and the
        // configuration file only references them; nothing secret enters the deployment package.
        val headerEnvVars = otlpHeaders.mapValues { (header, value) ->
            val envVar = "OTLP_HEADER_" + header.map { if (it.isLetterOrDigit()) it.uppercaseChar() else '_' }
                .joinToString("")
            emitter.lambdaEnvironment[envVar] = value
            envVar
        }
        emitter.lambdaFiles[COLLECTOR_CONFIG_FILE] = customCollectorConfig ?: collectorConfig(
            endpoint = if (otlpEndpoint.startsWith("http")) otlpEndpoint else "https://$otlpEndpoint",
            protocol = otlpProtocol,
            headerEnvVars = headerEnvVars,
            enableTraces = enableTraces,
            enableMetrics = enableMetrics,
        )
        COLLECTOR_CONFIG_VARS.forEach { emitter.lambdaEnvironment[it] = COLLECTOR_CONFIG_PATH }
    } else {
        // Default: the layer's built-in configuration, which exports traces to X-Ray using the
        // execution role - so that role needs write access. Its pipelines are fixed, so the
        // per-signal switches have nothing to act on.
        require(enableTraces && enableMetrics) {
            "enableTraces/enableMetrics require an otlpEndpoint; the X-Ray path uses the layer's built-in pipelines"
        }
        customCollectorConfig?.let {
            emitter.lambdaFiles[COLLECTOR_CONFIG_FILE] = it
            COLLECTOR_CONFIG_VARS.forEach { v -> emitter.lambdaEnvironment[v] = COLLECTOR_CONFIG_PATH }
        }
        emitter.attachXRayPolicy = true
    }

    // Sampling configuration (head-based only - for tail-based, use external collector)
    if (samplingRatio != null) {
        emitter.lambdaEnvironment["OTEL_TRACES_SAMPLER"] = "parentbased_traceidratio"
        emitter.lambdaEnvironment["OTEL_TRACES_SAMPLER_ARG"] = samplingRatio.toString()
    }

    // Propagation format (W3C Trace Context + X-Ray for AWS compatibility)
    emitter.lambdaEnvironment["OTEL_PROPAGATORS"] = "tracecontext,baggage,xray"

    // Lambda tracing config (Active mode enables X-Ray integration at infrastructure level)
    // Enable by default for X-Ray, disable for external OTLP endpoints to avoid duplicate traces
    val effectiveLambdaTracing = enableLambdaTracing ?: usingXRay
    if (effectiveLambdaTracing) {
        emitter.lambdaTracingMode = TerraformAwsServerlessBuilder.LambdaTracingMode.Active
    }

    // Point the application at the collector running beside it. Both ends are ours, so this link is
    // always gRPC regardless of the protocol the collector uses to reach the backend.
    val localCollectorUrl = "${OtlpProtocol.GRPC.urlScheme}://localhost:${OtlpProtocol.GRPC.defaultPort}"
    emitter.fulfillSetting(
        name, Json.encodeToJsonElement(
        OpenTelemetrySettings(
        url = localCollectorUrl,
        serviceName = effectiveServiceName,
        batching = OpenTelemetrySettings.BatchingRules(
            frequency = 10.seconds,  // Shorter batching for Lambda (limited execution time)
            maxQueueSize = 512,
            maxSize = 128,
            exportTimeout = 5.seconds
        ),
        sampling = samplingRatio?.let { OpenTelemetrySettings.Sampling(ratio = it, parentBased = true) }
    )))
}

/**
 * Builds the collector configuration: OTLP receiver on localhost, every signal exported to [endpoint].
 *
 * @param headerEnvVars Header name to the environment variable holding its value.
 */
internal fun collectorConfig(
    endpoint: String,
    protocol: OtlpProtocol,
    headerEnvVars: Map<String, String>,
    enableTraces: Boolean,
    enableMetrics: Boolean,
): String {
    val exporter = if (protocol == OtlpProtocol.HTTP) "otlphttp" else "otlp"
    return buildString {
        appendLine("receivers:")
        appendLine("  otlp:")
        appendLine("    protocols:")
        appendLine("      grpc:")
        appendLine("        endpoint: localhost:${OtlpProtocol.GRPC.defaultPort}")
        appendLine("      http:")
        appendLine("        endpoint: localhost:${OtlpProtocol.HTTP.defaultPort}")
        // No processors: the application's own SDK batches before it reaches the collector, and the
        // reduced collector in the Lambda layer publishes no list of the processors it ships with.
        appendLine("exporters:")
        appendLine("  $exporter:")
        appendLine("    endpoint: $endpoint")
        if (headerEnvVars.isNotEmpty()) {
            appendLine("    headers:")
            headerEnvVars.forEach { (header, envVar) ->
                // The file is written through a Terraform template, and "$${" is Terraform's escape
                // for a literal "${" - it is the collector, not Terraform, that resolves this.
                appendLine("      $header: \"\$\${env:$envVar}\"")
            }
        }
        appendLine("service:")
        appendLine("  pipelines:")
        listOfNotNull(
            "traces".takeIf { enableTraces },
            "metrics".takeIf { enableMetrics },
            "logs",
        ).forEach { pipeline ->
            appendLine("    $pipeline:")
            appendLine("      receivers: [otlp]")
            appendLine("      exporters: [$exporter]")
        }
    }
}

/**
 * Configures OpenTelemetry to export to Honeycomb via the ADOT Lambda layer.
 *
 * Honeycomb supports tail-based sampling via their Refinery product, which can be configured
 * to always keep traces with errors while sampling successful traces at a lower rate.
 *
 * @param collectorLayerVersion The ADOT collector layer version string.
 * @param layerVersion The layer version number.
 * @param dataset The Honeycomb dataset name (optional, defaults to service name).
 * @param samplingRatio Client-side sampling ratio (Honeycomb also supports server-side via Refinery).
 */
context(emitter: TerraformAwsServerlessBuilder<*>)
public fun TerraformNeed<TelemetryBackend.Settings>.otelHoneycomb(
    collectorLayerVersion: String = "0-117-0",
    layerVersion: Int = 1,
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

    val headers = buildMap {
        put("x-honeycomb-team", "\${var.honeycomb_api_key}")
        if (dataset != null) put("x-honeycomb-dataset", dataset)
    }

    otelCollector(
        collectorLayerVersion = collectorLayerVersion,
        layerVersion = layerVersion,
        otlpEndpoint = "https://api.honeycomb.io:443",
        otlpProtocol = otlpProtocol,
        otlpHeaders = headers,
        samplingRatio = samplingRatio,
        enableLambdaTracing = false,  // Don't enable X-Ray tracing for external backends
    )
}

/**
 * Configures OpenTelemetry to export to Grafana Cloud via the ADOT Lambda layer.
 *
 * @param collectorLayerVersion The ADOT collector layer version string.
 * @param layerVersion The layer version number.
 * @param instanceId Your Grafana Cloud instance ID.
 * @param zone The Grafana Cloud zone (e.g., "prod-us-east-0").
 * @param samplingRatio Client-side sampling ratio.
 * @param otlpProtocol Grafana Cloud's OTLP gateway only accepts OTLP over HTTP, so leave this alone
 *                     unless you are pointing at something else.
 */
context(emitter: TerraformAwsServerlessBuilder<*>)
public fun TerraformNeed<TelemetryBackend.Settings>.otelGrafanaCloud(
    collectorLayerVersion: String = "0-117-0",
    layerVersion: Int = 1,
    instanceId: String,
    zone: String = "prod-us-east-0",
    samplingRatio: Double? = null,
    otlpProtocol: OtlpProtocol = OtlpProtocol.HTTP,
): Unit {
    emitter.variable(object : TerraformNeed<String> {
        override val name: String = "grafana_cloud_api_key"
        override val serializer: KSerializer<String> = String.serializer()
        override val default: String? = null
        override val instructions: String =
            "Get an API key from Grafana Cloud: https://grafana.com/docs/grafana-cloud/account-management/authentication-and-permissions/access-policies/"
    })
    emitter.emit("variables") {
        "variable.grafana_cloud_api_key" {}
    }

    otelCollector(
        collectorLayerVersion = collectorLayerVersion,
        layerVersion = layerVersion,
        otlpEndpoint = "https://otlp-gateway-$zone.grafana.net/otlp",
        otlpProtocol = otlpProtocol,
        otlpHeaders = mapOf("Authorization" to "Basic \${base64encode(\"$instanceId:\${var.grafana_cloud_api_key}\")}"),
        samplingRatio = samplingRatio,
        enableLambdaTracing = false,  // Don't enable X-Ray tracing for external backends
    )
}

/**
 * Configures OpenTelemetry to export to AWS X-Ray (default, no external dependencies).
 *
 * X-Ray is the simplest option as it requires no additional infrastructure or API keys.
 * However, X-Ray has limited tail-based sampling support compared to dedicated observability platforms.
 *
 * This automatically:
 * - Attaches the X-Ray write access IAM policy to the Lambda role
 * - Enables Lambda's tracing_config with mode "Active"
 *
 * @param collectorLayerVersion The ADOT collector layer version string.
 * @param layerVersion The layer version number.
 * @param samplingRatio Client-side sampling ratio (0.0 to 1.0).
 */
context(emitter: TerraformAwsServerlessBuilder<*>)
public fun TerraformNeed<TelemetryBackend.Settings>.otelXRay(
    collectorLayerVersion: String = "0-117-0",
    layerVersion: Int = 1,
    samplingRatio: Double? = null,
): Unit {
    otelCollector(
        collectorLayerVersion = collectorLayerVersion,
        layerVersion = layerVersion,
        otlpEndpoint = null,  // null means use X-Ray
        samplingRatio = samplingRatio,
        enableLambdaTracing = true,  // Enable X-Ray tracing at Lambda level
    )
}
