package com.lightningkite.lightningserver.aws

import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.serverhealth.ServerHealth
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.http.HttpMetric
import software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient
import software.amazon.awssdk.http.crt.AwsCrtHttpClient
import software.amazon.awssdk.metrics.MetricCollection
import software.amazon.awssdk.metrics.MetricPublisher
import kotlin.math.roundToInt

object AwsConnections {
    val client = AwsCrtHttpClient.builder()
        .build() as AwsCrtHttpClient
    val asyncClient = AwsCrtAsyncHttpClient.builder()
        .build() as AwsCrtAsyncHttpClient
    var total: Int = Int.MAX_VALUE
    var used: Int = 0
    val health: HealthStatus get() = when(val amount = used / total.toFloat()) {
            in 0f ..< 0.7f -> HealthStatus(HealthStatus.Level.OK)
            in 0.7f ..< 0.95f -> HealthStatus(HealthStatus.Level.WARNING, additionalMessage = "Connection utilization: ${amount.times(100).roundToInt()}%")
            in 0.95f ..< 1f -> HealthStatus(HealthStatus.Level.URGENT, additionalMessage = "Connection utilization: ${amount.times(100).roundToInt()}%")
            else -> HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Connection utilization: ${amount.times(100).roundToInt()}%")
    }
    val clientOverrideConfiguration = ClientOverrideConfiguration.builder()
        .addMetricPublisher(object: MetricPublisher {

            override fun publish(metrics: MetricCollection) {
                metrics.childrenWithName("ApiCallAttempt")?.forEach {
                    it.childrenWithName("HttpClient")?.forEach {
                        it.metricValues(HttpMetric.MAX_CONCURRENCY).firstOrNull()?.let { total = it }
                        it.metricValues(HttpMetric.LEASED_CONCURRENCY).firstOrNull()?.let { used = it }
                    }
                }
            }

            override fun close() {}
        })
        .build()
}