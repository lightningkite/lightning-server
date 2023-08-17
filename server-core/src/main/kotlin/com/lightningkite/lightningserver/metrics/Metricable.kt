package com.lightningkite.lightningserver.metrics

interface Metricable<T> {
    fun withMetrics(metricsKeyName: String): T
}