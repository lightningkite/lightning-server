package com.lightningkite.lightningserver.aws

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.http.crt.AwsCrtAsyncHttpClient
import software.amazon.awssdk.http.crt.AwsCrtHttpClient
import software.amazon.awssdk.metrics.LoggingMetricPublisher
import software.amazon.awssdk.metrics.MetricCollection
import software.amazon.awssdk.metrics.MetricPublisher
import software.amazon.awssdk.metrics.SdkMetric

object AwsConnections {
    val client = AwsCrtHttpClient.builder()
        .build() as AwsCrtHttpClient
    val asyncClient = AwsCrtAsyncHttpClient.builder()
        .build() as AwsCrtAsyncHttpClient
    var utilization: Float = 0f
    val clientOverrideConfiguration = ClientOverrideConfiguration.builder()
        .addMetricPublisher(object: MetricPublisher {
            val metricLeasedConcurrency = SdkMetric.create<Int>("LeasedConcurrency", Int::class.javaObjectType, MetricLevel)
            override fun publish(metrics: MetricCollection) {
                AwsCrtHttpClient.
                metrics.metricValues<>(SdkMetric)
            }

            override fun close() {}
        })
        .build()
}