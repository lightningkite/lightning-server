// by Claude
package com.lightningkite.lightningserver.terraform.awsserverless

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class GrafanaAlertsTerraformTest {

    @Test
    fun `GrafanaAlert httpErrors returns two alerts with correct defaults`() {
        val alerts = GrafanaAlert.httpErrors("my-service")
        assertEquals(2, alerts.size)

        val countAlert = alerts[0]
        assertEquals("High 5xx Error Rate", countAlert.name)
        assertEquals("critical", countAlert.severity)
        assertTrue(countAlert.promql.contains("""service_name="my-service""""))
        assertTrue(countAlert.promql.contains("http_server_errors_total"))
        assertTrue(countAlert.promql.contains("> 5"))

        val spikeAlert = alerts[1]
        assertEquals("5xx Error Rate Spike", spikeAlert.name)
        assertEquals("warning", spikeAlert.severity)
        assertTrue(spikeAlert.promql.contains("""service_name="my-service""""))
        assertTrue(spikeAlert.promql.contains("> 10"))
    }

    @Test
    fun `GrafanaAlert httpErrors respects custom thresholds`() {
        val alerts = GrafanaAlert.httpErrors("svc", errorThreshold = 20, spikeMultiplier = 5)
        assertTrue(alerts[0].promql.contains("> 20"))
        assertTrue(alerts[1].promql.contains("> 5"))
    }

    @Test
    fun `httpLatencyAlerts returns two alerts with correct thresholds`() {
        val alerts = GrafanaAlert.httpLatency("my-service")
        assertEquals(2, alerts.size)

        val p99 = alerts[0]
        assertEquals("High P99 Latency", p99.name)
        assertTrue(p99.promql.contains("0.99"))
        assertTrue(p99.promql.contains("> 5000"))
        assertTrue(p99.promql.contains("http_server_request_duration_bucket"))

        val p95 = alerts[1]
        assertEquals("High P95 Latency", p95.name)
        assertTrue(p95.promql.contains("0.95"))
        assertTrue(p95.promql.contains("> 3000"))
    }

    @Test
    fun `httpLatencyAlerts respects custom thresholds`() {
        val alerts = GrafanaAlert.httpLatency("svc", p99ThresholdMs = 10000, p95ThresholdMs = 7000)
        assertTrue(alerts[0].promql.contains("> 10000"))
        assertTrue(alerts[1].promql.contains("> 7000"))
    }

    @Test
    fun `httpLivenessAlerts returns one alert with correct window`() {
        val alerts = GrafanaAlert.httpLiveness("my-service")
        assertEquals(1, alerts.size)

        val alert = alerts[0]
        assertEquals("No Traffic Detected", alert.name)
        assertTrue(alert.promql.contains("[15m]"))
        assertTrue(alert.promql.contains("== 0"))
        assertEquals(15.minutes, alert.forDuration)
    }

    @Test
    fun `httpLivenessAlerts respects custom window`() {
        val alerts = GrafanaAlert.httpLiveness("svc", windowMinutes = 30)
        assertTrue(alerts[0].promql.contains("[30m]"))
        assertEquals(30.minutes, alerts[0].forDuration)
    }

    @Test
    fun `alert lists compose correctly`() {
        val all = GrafanaAlert.httpErrors("svc") +
                GrafanaAlert.httpLatency("svc") +
                GrafanaAlert.httpLiveness("svc")
        assertEquals(5, all.size)
    }

    @Test
    fun `custom GrafanaAlert works alongside builders`() {
        val custom = GrafanaAlert(
            name = "Custom Alert",
            promql = """some_metric{service="svc"} > 100""",
            severity = "critical",
        )
        val all = GrafanaAlert.httpErrors("svc") + listOf(custom)
        assertEquals(3, all.size)
        assertEquals("Custom Alert", all[2].name)
    }

    @Test
    fun `all alerts contain service name in promql`() {
        val serviceName = "test-service-123"
        val all = GrafanaAlert.httpErrors(serviceName) +
                GrafanaAlert.httpLatency(serviceName) +
                GrafanaAlert.httpLiveness(serviceName)
        for (alert in all) {
            assertTrue(
                alert.promql.contains(serviceName),
                "Alert '${alert.name}' should contain service name in PromQL"
            )
        }
    }
}
