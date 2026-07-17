// by Claude
package com.lightningkite.lightningserver.terraform.awsserverless

import com.lightningkite.services.data.EmailAddress
import com.lightningkite.services.terraform.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A single Grafana Cloud alert rule definition.
 *
 * Use the companion object builder functions [GrafanaAlert.httpErrors], [GrafanaAlert.httpLatency],
 * and [GrafanaAlert.httpLiveness] to create standard alerts, or construct custom instances directly.
 *
 * @param name Human-readable alert name (e.g., "High 5xx Error Rate").
 * @param promql PromQL expression that evaluates to a boolean vector (includes the threshold comparison).
 *               OTel metric names use underscores and Prometheus suffixes: `http_server_errors_total`,
 *               `http_server_request_duration_bucket`, etc.
 * @param forDuration How long the condition must be true before firing.
 * @param severity Alert severity label: "critical" or "warning".
 * @param summary Short annotation shown in alert notifications.
 * @param description Longer annotation with context for the on-call engineer.
 */
public data class GrafanaAlert(
    val name: String,
    val promql: String,
    val forDuration: Duration = 5.minutes,
    val severity: String = "warning",
    val summary: String = name,
    val description: String = "",
) {
    public companion object {
        /**
         * Returns alerts for HTTP 5xx error conditions.
         *
         * Produces two alerts:
         * 1. **Absolute error count** — fires when total 5xx errors in the evaluation window exceed [errorThreshold].
         * 2. **Error rate spike** — fires when the 5-minute error rate exceeds [spikeMultiplier]x the 30-minute baseline.
         *
         * @param serviceName The `service_name` label value in Prometheus (matches OTEL_SERVICE_NAME).
         * @param errorThreshold Number of 5xx errors in a 5-minute window to trigger the absolute alert.
         * @param spikeMultiplier How many times above the 30-minute baseline rate triggers the spike alert.
         */
        public fun httpErrors(
            serviceName: String,
            errorThreshold: Int = 5,
            spikeMultiplier: Int = 10,
        ): List<GrafanaAlert> = listOf(
            GrafanaAlert(
                name = "High 5xx Error Rate",
                promql = """sum(increase(http_server_errors_total{service_name="$serviceName"}[5m])) > $errorThreshold""",
                forDuration = 5.minutes,
                severity = "critical",
                summary = "High rate of 5xx errors detected",
                description = "More than $errorThreshold server errors in the last 5 minutes for service $serviceName.",
            ),
            GrafanaAlert(
                name = "5xx Error Rate Spike",
                promql = """(sum(rate(http_server_response_status_category_total{service_name="$serviceName", http_status_category="5xx"}[5m]))) / (sum(rate(http_server_response_status_category_total{service_name="$serviceName", http_status_category="5xx"}[30m])) + 0.001) > $spikeMultiplier""",
                forDuration = 5.minutes,
                severity = "warning",
                summary = "5xx error rate spike detected",
                description = "5xx error rate has increased more than ${spikeMultiplier}x over the 30-minute baseline for service $serviceName.",
            ),
        )

        /**
         * Returns alerts for HTTP request latency.
         *
         * Produces two alerts:
         * 1. **P99 latency** — fires when the 99th percentile request duration exceeds [p99ThresholdMs].
         * 2. **P95 latency** — fires when the 95th percentile request duration exceeds [p95ThresholdMs].
         *
         * @param serviceName The `service_name` label value in Prometheus.
         * @param p99ThresholdMs P99 latency threshold in milliseconds.
         * @param p95ThresholdMs P95 latency threshold in milliseconds.
         */
        public fun httpLatency(
            serviceName: String,
            p99ThresholdMs: Long = 5000,
            p95ThresholdMs: Long = 3000,
        ): List<GrafanaAlert> = listOf(
            GrafanaAlert(
                name = "High P99 Latency",
                promql = """histogram_quantile(0.99, sum(rate(http_server_request_duration_bucket{service_name="$serviceName"}[5m])) by (le)) > $p99ThresholdMs""",
                forDuration = 5.minutes,
                severity = "warning",
                summary = "P99 request latency is too high",
                description = "P99 latency exceeds ${p99ThresholdMs}ms for service $serviceName.",
            ),
            GrafanaAlert(
                name = "High P95 Latency",
                promql = """histogram_quantile(0.95, sum(rate(http_server_request_duration_bucket{service_name="$serviceName"}[5m])) by (le)) > $p95ThresholdMs""",
                forDuration = 5.minutes,
                severity = "warning",
                summary = "P95 request latency is too high",
                description = "P95 latency exceeds ${p95ThresholdMs}ms for service $serviceName.",
            ),
        )

        /**
         * Returns an alert that fires when no HTTP traffic is received.
         *
         * Useful for detecting complete outages. Only recommended for production deployments
         * that expect continuous traffic.
         *
         * @param serviceName The `service_name` label value in Prometheus.
         * @param windowMinutes How many minutes of zero traffic before firing.
         */
        public fun httpLiveness(
            serviceName: String,
            windowMinutes: Int = 15,
        ): List<GrafanaAlert> = listOf(
            GrafanaAlert(
                name = "No Traffic Detected",
                promql = """sum(rate(http_server_request_count_total{service_name="$serviceName"}[${windowMinutes}m])) == 0""",
                forDuration = windowMinutes.minutes,
                severity = "warning",
                summary = "No traffic detected",
                description = "No HTTP requests received in the last $windowMinutes minutes for service $serviceName. Service may be down.",
            ),
        )
    }
}

/**
 * Creates Grafana Cloud alert rules via the Grafana Terraform provider.
 *
 * This emits terraform resources for a Grafana folder, contact point, and rule group
 * containing the provided [alerts]. Call this after [otelGrafanaCloud] which sets up data export.
 *
 * ## Prerequisites
 * - Call [otelGrafanaCloud] first to set up OTel data export to Grafana Cloud.
 * - Create a Grafana Cloud Service Account with Editor role and generate a token.
 *   This is separate from the OTLP ingest API key used by [otelGrafanaCloud].
 *
 * ## Notification Routing
 * Alert rules are created in a project-specific folder. A contact point is created with the
 * provided [contactEmail]. To route alerts to this contact point, configure a notification
 * policy in the Grafana UI that matches on the folder, or add a `grafana_notification_policy`
 * resource to your terraform.
 *
 * ## Example
 * ```kotlin
 * override fun Server.settings() {
 *     telemetrySettings.otelGrafanaCloud(instanceId = "123456", zone = "prod-us-east-0")
 *     grafanaAlerts(
 *         grafanaCloudStackSlug = "myteam",
 *         alerts = GrafanaAlert.httpErrors(displayName, errorThreshold = 5) +
 *                  GrafanaAlert.httpLatency(displayName, p99ThresholdMs = 5000) +
 *                  GrafanaAlert.httpLiveness(displayName),
 *     )
 * }
 * ```
 *
 * @param grafanaCloudStackSlug Your Grafana Cloud stack slug (e.g., "myteam" from myteam.grafana.net).
 * @param alerts The alert rules to create. Use [GrafanaAlert.httpErrors], [GrafanaAlert.httpLatency],
 *               and [GrafanaAlert.httpLiveness] to build standard alerts, or construct [GrafanaAlert] directly.
 * @param contactEmail Email address for alert notifications. Defaults to [TerraformAwsServerlessBuilder.emergencyContact].
 * @param evaluationIntervalSeconds How often Grafana evaluates the alert rules.
 */
context(emitter: TerraformAwsServerlessBuilder<*>)
public fun grafanaAlerts(
    grafanaCloudStackSlug: String,
    alerts: List<GrafanaAlert>,
    contactEmail: EmailAddress = emitter.emergencyContact,
    evaluationIntervalSeconds: Int = 60,
): Unit {
    if (alerts.isEmpty()) return

    // Register Grafana provider
    emitter.require(TerraformProviderImport.grafana)
    emitter.require(
        TerraformProvider(
            TerraformProviderImport.grafana,
            null,
            buildJsonObject {
                put("url", "https://$grafanaCloudStackSlug.grafana.net")
                put("auth", "\${var.grafana_cloud_service_account_token}")
            }
        )
    )

    // Declare variable for service account token
    emitter.variable(object : TerraformNeed<String> {
        override val name: String = "grafana_cloud_service_account_token"
        override val serializer: KSerializer<String> = String.serializer()
        override val default: String? = null
        override val instructions: String =
            "Create a Grafana Cloud Service Account with Editor role at " +
            "https://$grafanaCloudStackSlug.grafana.net/org/serviceaccounts, " +
            "then generate a token."
    })
    emitter.emit("variables") {
        "variable.grafana_cloud_service_account_token" {}
    }

    val safePrefix = emitter.projectPrefix.lowercase().filter { it.isLetterOrDigit() || it == '_' }

    emitter.emit("grafanaAlerts") {
        // Look up the built-in Prometheus/Mimir data source
        "data.grafana_data_source.mimir_$safePrefix" {
            "name" - "grafanacloud-metrics"
        }

        // Create a folder to isolate this project's alerts
        "resource.grafana_folder.$safePrefix" {
            "title" - "${emitter.displayName} Alerts"
        }

        // Create a contact point for this project
        "resource.grafana_contact_point.$safePrefix" {
            "name" - "${emitter.displayName} Alerts"
            "email" {
                "addresses" - listOf(contactEmail.raw)
                "single_email" - true
            }
        }

        // Create rule group with all alerts
        "resource.grafana_rule_group.$safePrefix" {
            "name" - "${emitter.displayName} Application Alerts"
            "folder_uid" - expression("grafana_folder.$safePrefix.uid")
            "interval_seconds" - evaluationIntervalSeconds

            for (alert in alerts) {
                "rule" - terraformJsonObject {
                    "name" - "${emitter.displayName}: ${alert.name}"
                    "condition" - "A"
                    "for" - "${alert.forDuration.inWholeSeconds}s"

                    "annotations" {
                        "summary" - alert.summary
                        "description" - alert.description
                    }
                    "labels" {
                        "severity" - alert.severity
                        "service" - emitter.projectPrefix
                    }

                    // Single data block: the PromQL expression includes the threshold,
                    // so Grafana evaluates it as a boolean vector directly.
                    "data" - terraformJsonObject {
                        "ref_id" - "A"
                        "relative_time_range" {
                            "from" - 600
                            "to" - 0
                        }
                        "datasource_uid" - expression("data.grafana_data_source.mimir_$safePrefix.uid")
                        "model" - Json.encodeToString(
                            buildJsonObject {
                                put("expr", alert.promql)
                                put("refId", "A")
                            }
                        )
                    }
                }
            }
        }
    }
}
