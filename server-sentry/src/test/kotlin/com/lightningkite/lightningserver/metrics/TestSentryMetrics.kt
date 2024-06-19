package com.lightningkite.lightningserver.metrics

import com.lightningkite.now
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TestSentryMetrics {


    @Test
    fun testMetrics(): Unit = runBlocking {

        val metrics = SentryMetricsReporter(
            settings = MetricSettings("", emptySet(), emptySet()),
            dsn = "https://a8c641dc23041af5143f1aa3480a59fb@sentry24.lightningkite.com/5"
        )

        metrics.report(
            listOf(
                MetricEvent(
                    metricType = MetricType("Test Metric", MetricUnit.Other),
                    entryPoint = null,
                    time = now(),
                    value = 1.0
                )
            )
        )


    }


}