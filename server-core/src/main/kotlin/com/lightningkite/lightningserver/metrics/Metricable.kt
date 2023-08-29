package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningserver.core.Disconnectable

interface Metricable<T>: Disconnectable {
    fun withMetrics(metricsKeyName: String): T
}