package com.lightningkite.lightningserver.metrics

import com.lightningkite.lightningserver.services.Disconnectable

interface Metricable {
    fun applyMetrics(metrics: Metrics, metricsKeyName: String)
}
