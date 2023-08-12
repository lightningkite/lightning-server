package com.lightningkite.lightningserver.metrics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class CloudwatchMetricsTest {
    @Test
    fun metricsTest(): Unit = runBlocking {
        CloudwatchMetrics
        MetricSettings(
            url = "cloudwatch://us-west-2"
        )().report(List(10) {
            MetricEvent(
                type = "Test Metric",
                entryPoint = "GET /",
                time = Instant.now(),
                value = Random.nextDouble()
            )
        })
    }
}