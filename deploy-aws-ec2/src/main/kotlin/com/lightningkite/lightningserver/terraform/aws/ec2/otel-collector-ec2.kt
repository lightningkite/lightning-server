package com.lightningkite.lightningserver.terraform.aws.ec2

import com.lightningkite.services.otel.OpenTelemetrySettings
import com.lightningkite.services.terraform.AwsPolicyStatement
import com.lightningkite.services.terraform.TerraformJsonObject.Companion.expression
import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

/**
 * Default pinned Grafana Alloy release. Pinned (not `latest`) so the baked golden AMI is
 * reproducible; bumping it changes the install script, which re-bakes the scaling builder's AMI via
 * its install-script hash. Check https://github.com/grafana/alloy/releases for newer versions.
 */
private const val DEFAULT_ALLOY_VERSION = "v1.17.0"

/**
 * Default pinned AWS Distro for OpenTelemetry (ADOT) Collector release. Pinned for the same
 * reproducible-AMI reason as [DEFAULT_ALLOY_VERSION]. Check
 * https://github.com/aws-observability/aws-otel-collector/releases for newer versions.
 */
private const val DEFAULT_ADOT_VERSION = "v0.48.0"

/** Names that get folded into shell heredocs / URLs; reject anything that could break out of them. */
private val SAFE_IDENTIFIER = Regex("^[A-Za-z0-9._-]+$")

/**
 * Runs a [Grafana Alloy](https://grafana.com/docs/alloy/) collector on each instance and exports
 * the application's OpenTelemetry data to [Grafana Cloud](https://grafana.com/products/cloud/).
 *
 * Alloy is Grafana's supported successor to the Grafana Agent. It runs as an on-box systemd service
 * that receives OTLP from the application on `localhost` and forwards traces, metrics, and logs to
 * Grafana Cloud's OTLP gateway:
 *
 * ```
 * App (otlp-grpc://localhost:4317) -> Grafana Alloy (systemd) -> Grafana Cloud OTLP gateway
 * ```
 *
 * Works on both the single-instance and scaling/ASG builders. The application is pointed at the
 * local collector via [telemetrySettings][com.lightningkite.lightningserver.definition.telemetrySettings],
 * so it never needs the backend credential itself.
 *
 * ## Credentials
 *
 * The Grafana Cloud token is supplied at apply time through the Terraform variable
 * `grafana_cloud_api_key` (sensitive), stored in SSM Parameter Store as a `SecureString`, and
 * fetched by Alloy at service start via the instance role. It is never written into `user_data` or
 * baked into the AMI. Create an access-policy token with the metrics/traces/logs **write** scopes:
 * https://grafana.com/docs/grafana-cloud/account-management/authentication-and-permissions/access-policies/
 *
 * @param grafanaCloudInstanceId Your Grafana Cloud OTLP username / instance ID (the numeric stack
 *        id shown next to the OTLP endpoint in the Grafana Cloud portal). Not a secret.
 * @param zone The Grafana Cloud zone, e.g. `prod-us-east-0`; forms the OTLP gateway hostname.
 * @param serviceName The `service.name` reported in telemetry. Defaults to the project prefix.
 * @param samplingRatio Optional head-based trace sampling ratio (0.0–1.0). Null = 100%.
 * @param alloyVersion The pinned Grafana Alloy release tag to install (see [DEFAULT_ALLOY_VERSION]).
 */
context(emitter: TerraformAwsEc2BuilderBase<*>)
public fun TerraformNeed<OpenTelemetrySettings?>.otelGrafanaCloud(
    grafanaCloudInstanceId: String,
    zone: String = "prod-us-east-0",
    serviceName: String? = null,
    samplingRatio: Double? = null,
    alloyVersion: String = DEFAULT_ALLOY_VERSION,
) {
    require(SAFE_IDENTIFIER.matches(grafanaCloudInstanceId)) {
        "grafanaCloudInstanceId '$grafanaCloudInstanceId' must contain only letters, digits, '.', '-', '_'"
    }
    require(SAFE_IDENTIFIER.matches(zone)) {
        "zone '$zone' must contain only letters, digits, '.', '-', '_'"
    }
    require(SAFE_IDENTIFIER.matches(alloyVersion)) {
        "alloyVersion '$alloyVersion' must contain only letters, digits, '.', '-', '_'"
    }

    val endpoint = "https://otlp-gateway-$zone.grafana.net/otlp"

    // Declare the sensitive Terraform variable that carries the Grafana Cloud token at apply time.
    emitter.variable(object : TerraformNeed<String> {
        override val name: String = "grafana_cloud_api_key"
        override val serializer: KSerializer<String> = String.serializer()
        override val default: String? = null
        override val instructions: String =
            "Grafana Cloud OTLP access-policy token with metrics/traces/logs write scopes. " +
                "See https://grafana.com/docs/grafana-cloud/account-management/authentication-and-permissions/access-policies/"
    })
    emitter.emit("variables") {
        "variable.grafana_cloud_api_key" {
            "type" - "string"
            "sensitive" - true
        }
    }

    // Stash the token in SSM SecureString; the instance role may read it (set up in the base).
    val parameterName = "/${emitter.projectPrefix}/grafana-cloud-api-key"
    emitter.emitInstanceSecret(
        resourceId = "grafana_cloud_api_key",
        parameterName = parameterName,
        valueExpression = expression("var.grafana_cloud_api_key"),
        description = "Grafana Cloud OTLP token for ${emitter.displayName} Alloy collector",
    )

    // Install + configure Alloy on every instance (cloud-init for single, AMI bake for scaling).
    emitter.provisioningFragments += run {
        val arch =
            if (emitter.instanceArchitecture == TerraformAwsEc2BuilderBase.CPUArchitecture.Arm) "arm64" else "amd64"
        // Alloy River config: OTLP receiver on localhost -> batch -> OTLP/HTTP export to Grafana Cloud,
        // authed via Basic auth whose password is read from the file the fetch script writes.
        val config = """
local.file "grafana_key" {
  filename  = "/run/alloy/key"
  is_secret = true
}

otelcol.receiver.otlp "default" {
  grpc {
    endpoint = "127.0.0.1:4317"
  }
  http {
    endpoint = "127.0.0.1:4318"
  }
  output {
    metrics = [otelcol.processor.batch.default.input]
    logs    = [otelcol.processor.batch.default.input]
    traces  = [otelcol.processor.batch.default.input]
  }
}

otelcol.processor.batch "default" {
  output {
    metrics = [otelcol.exporter.otlphttp.grafana.input]
    logs    = [otelcol.exporter.otlphttp.grafana.input]
    traces  = [otelcol.exporter.otlphttp.grafana.input]
  }
}

otelcol.auth.basic "grafana" {
  username = "$grafanaCloudInstanceId"
  password = local.file.grafana_key.content
}

otelcol.exporter.otlphttp "grafana" {
  client {
    endpoint = "$endpoint"
    auth     = otelcol.auth.basic.grafana.handler
  }
}
""".trim()
        // Fetched at service start (ExecStartPre) as the alloy user; the instance role grants SSM read.
        val fetchScript = """
#!/bin/bash
set -euo pipefail
aws ssm get-parameter --name "$parameterName" --with-decryption --query Parameter.Value --output text --region "${emitter.applicationRegion}" > /run/alloy/key
chmod 600 /run/alloy/key
""".trim()
        // RuntimeDirectory creates /run/alloy (owned by alloy) before ExecStartPre runs; StateDirectory
        // creates /var/lib/alloy for the collector's WAL. The collector's own HTTP UI is bound to
        // localhost so it is never exposed.
        val unit = """
[Unit]
Description=Grafana Alloy (OpenTelemetry collector for ${emitter.displayName})
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=alloy
Group=alloy
RuntimeDirectory=alloy
RuntimeDirectoryMode=0700
StateDirectory=alloy
ExecStartPre=/usr/local/bin/alloy-fetch-secret
ExecStart=/usr/local/bin/alloy run /etc/alloy/config.alloy --storage.path=/var/lib/alloy --server.http.listen-addr=127.0.0.1:12345
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
""".trim()
        // language="Shell Script"
        buildString {
            this.appendLine("# === Grafana Alloy (OpenTelemetry collector) ===")
            this.appendLine("echo \"[INFO] Installing Grafana Alloy $alloyVersion at \$(date)\"")
            this.appendLine("id -u alloy >/dev/null 2>&1 || useradd --system --user-group --no-create-home --shell /usr/sbin/nologin alloy")
            this.appendLine("curl -fsSL -o /tmp/alloy.zip https://github.com/grafana/alloy/releases/download/$alloyVersion/alloy-linux-${arch}.zip")
            this.appendLine("unzip -o /tmp/alloy.zip -d /tmp/alloy-extract")
            this.appendLine("install -m 0755 /tmp/alloy-extract/alloy-linux-${arch} /usr/local/bin/alloy")
            this.appendLine("rm -rf /tmp/alloy.zip /tmp/alloy-extract")
            this.appendLine()
            this.appendLine("mkdir -p /etc/alloy")
            this.appendLine("cat > /etc/alloy/config.alloy << 'ALLOY_CONFIG_EOF'")
            this.appendLine(config)
            this.appendLine("ALLOY_CONFIG_EOF")
            this.appendLine()
            this.appendLine("cat > /usr/local/bin/alloy-fetch-secret << 'ALLOY_FETCH_EOF'")
            this.appendLine(fetchScript)
            this.appendLine("ALLOY_FETCH_EOF")
            this.appendLine("chmod 0755 /usr/local/bin/alloy-fetch-secret")
            this.appendLine()
            this.appendLine("cat > /etc/systemd/system/alloy.service << 'ALLOY_UNIT_EOF'")
            this.appendLine(unit)
            this.appendLine("ALLOY_UNIT_EOF")
            this.appendLine("systemctl enable alloy")
        }.trim()
    }
    emitter.provisioningServices += "alloy"

    // Point the application at the local collector. It batches/exports to Grafana Cloud, so the app
    // needs no backend credential of its own.
    emitter.fulfillLocalCollector(name, serviceName, samplingRatio)
}


/**
 * Emits an SSM `SecureString` parameter holding a secret (typically a Terraform variable
 * expression) and grants the EC2 instance role permission to read it. The value is encrypted
 * with the shared customer-managed key when one is configured, otherwise the SSM-managed key.
 *
 * This is the secure channel for credentials an on-box agent needs at runtime (e.g. the OTel
 * collector's backend API key): the secret lives only in SSM, never in `user_data` or the baked
 * AMI, and the agent fetches it at service start via the instance role.
 *
 * @param resourceId Terraform-safe resource identifier (letters/digits/underscores).
 * @param parameterName The SSM parameter name (e.g. `/$projectPrefix/grafana-cloud-api-key`).
 * @param valueExpression The parameter value, usually `expression("var.<name>")`.
 */
internal fun TerraformAwsEc2BuilderBase<*>.emitInstanceSecret(
    resourceId: String,
    parameterName: String,
    valueExpression: String,
    description: String,
) {
    policyStatements += AwsPolicyStatement(
        action = listOf("ssm:GetParameter"),
        resource = listOf(expression("aws_ssm_parameter.$resourceId.arn"))
    )
    // The instance fetches this at boot, so it must exist before the instance/ASG is created.
    instanceSecretDependencies += "aws_ssm_parameter.$resourceId"
    emit("telemetry") {
        "resource.aws_ssm_parameter.$resourceId" {
            "name" - parameterName
            "type" - "SecureString"
            "value" - valueExpression
            sharedKmsKeyArn?.let { "key_id" - it }
            "description" - description
        }
    }
}


/**
 * Runs an [AWS Distro for OpenTelemetry](https://aws-otel.github.io/) (ADOT) collector on each
 * instance and exports the application's telemetry to **AWS X-Ray** (traces) and, optionally,
 * **CloudWatch** (metrics, via the EMF exporter).
 *
 * ```
 * App (otlp-grpc://localhost:4317) -> ADOT Collector (systemd) -> X-Ray + CloudWatch
 * ```
 *
 * This is the no-external-dependencies option: it needs no API key and no SSM secret. The collector
 * authenticates to X-Ray/CloudWatch with the instance role, so this just attaches the X-Ray write
 * policy (CloudWatch metric publishing is already covered by the `CloudWatchAgentServerPolicy` the
 * base attaches). Works on both the single-instance and scaling/ASG builders.
 *
 * Trace sampling is applied in the application's OpenTelemetry SDK via [samplingRatio]; the collector
 * forwards everything it receives.
 *
 * @param serviceName The `service.name` reported in telemetry. Defaults to the project prefix.
 * @param samplingRatio Optional head-based trace sampling ratio (0.0–1.0). Null = 100%.
 * @param enableMetrics Whether to also export metrics to CloudWatch via the EMF exporter (default true).
 * @param adotVersion The pinned ADOT collector release to install (see [DEFAULT_ADOT_VERSION]).
 */
context(emitter: TerraformAwsEc2BuilderBase<*>)
public fun TerraformNeed<OpenTelemetrySettings?>.otelXRay(
    serviceName: String? = null,
    samplingRatio: Double? = null,
    enableMetrics: Boolean = true,
    adotVersion: String = DEFAULT_ADOT_VERSION,
) {
    require(SAFE_IDENTIFIER.matches(adotVersion)) {
        "adotVersion '$adotVersion' must contain only letters, digits, '.', '-', '_'"
    }

    // The collector writes to X-Ray (and CloudWatch when metrics are on) with the instance role.
    emitter.attachXRayPolicy = true

    emitter.provisioningFragments += adotProvisioningFragment(
        adotVersion = adotVersion,
        arch = if (emitter.instanceArchitecture == TerraformAwsEc2BuilderBase.CPUArchitecture.Arm) "arm64" else "amd64",
        region = emitter.applicationRegion,
        namespace = emitter.projectPrefix,
        enableMetrics = enableMetrics,
    )
    emitter.provisioningServices += "aws-otel-collector"

    emitter.fulfillLocalCollector(name, serviceName, samplingRatio)
}

/**
 * Builds the shell fragment that installs the ADOT collector `.deb`, writes its config to the
 * package's default config path, and `enable`s the packaged systemd service.
 */
private fun adotProvisioningFragment(
    adotVersion: String,
    arch: String,
    region: String,
    namespace: String,
    enableMetrics: Boolean,
): String {
    // Standard OTel Collector config: OTLP receiver on localhost -> batch -> AWS exporters. The
    // awsxray/awsemf exporters resolve credentials from the instance role.
    val config = buildString {
        appendLine(
            """
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 127.0.0.1:4317
      http:
        endpoint: 127.0.0.1:4318
processors:
  batch: {}
exporters:
  awsxray:
    region: $region""".trim()
        )
        if (enableMetrics) {
            appendLine("  awsemf:")
            appendLine("    region: $region")
            appendLine("    namespace: $namespace")
        }
        appendLine("service:")
        appendLine("  pipelines:")
        appendLine("    traces:")
        appendLine("      receivers: [otlp]")
        appendLine("      processors: [batch]")
        appendLine("      exporters: [awsxray]")
        if (enableMetrics) {
            appendLine("    metrics:")
            appendLine("      receivers: [otlp]")
            appendLine("      processors: [batch]")
            appendLine("      exporters: [awsemf]")
        }
    }.trim()

    // language="Shell Script"
    return buildString {
        appendLine("# === AWS Distro for OpenTelemetry collector ===")
        appendLine("echo \"[INFO] Installing ADOT collector $adotVersion at \$(date)\"")
        appendLine("curl -fsSL -o /tmp/aws-otel-collector.deb https://aws-otel-collector.s3.amazonaws.com/ubuntu/$arch/$adotVersion/aws-otel-collector.deb")
        appendLine("dpkg -i /tmp/aws-otel-collector.deb")
        appendLine("rm -f /tmp/aws-otel-collector.deb")
        appendLine()
        appendLine("cat > /opt/aws/aws-otel-collector/etc/config.yaml << 'ADOT_CONFIG_EOF'")
        appendLine(config)
        appendLine("ADOT_CONFIG_EOF")
        appendLine("systemctl enable aws-otel-collector")
    }.trim()
}

/**
 * Points the application's telemetry setting at the on-box collector listening on localhost. Shared
 * by the collector helpers; the collector forwards to whichever backend it was configured for, so
 * the application never needs the backend's credentials or endpoint.
 */
private fun TerraformAwsEc2BuilderBase<*>.fulfillLocalCollector(
    settingName: String,
    serviceName: String?,
    samplingRatio: Double?,
) {
    fulfillSetting(
        settingName,
        Json.encodeToJsonElement(
            OpenTelemetrySettings(
                url = "otlp-grpc://localhost:4317",
                serviceName = serviceName ?: projectPrefix,
                sampling = samplingRatio?.let { OpenTelemetrySettings.Sampling(ratio = it) },
            )
        )
    )
}