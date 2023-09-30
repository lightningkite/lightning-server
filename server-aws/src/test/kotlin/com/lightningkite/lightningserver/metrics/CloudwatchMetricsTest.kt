package com.lightningkite.lightningserver.metrics

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import com.lightningkite.now
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import kotlinx.datetime.Instant
import kotlin.random.Random

class CloudwatchMetricsTest {
    @Test
    @Ignore
    fun metricsTest(): Unit = runBlocking {
        CloudwatchMetrics
        val testMetric = MetricType("Test Metric", MetricUnit.Percent)
        MetricSettings(
            url = "cloudwatch://us-west-2/My Project?resolution=60"
        )().report(List(10) {
            MetricEvent(
                testMetric,
                entryPoint = "GET /",
                time = now(),
                value = Random.nextDouble()
            )
        })
    }
}