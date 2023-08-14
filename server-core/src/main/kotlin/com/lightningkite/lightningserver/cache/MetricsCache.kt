package com.lightningkite.lightningserver.cache

import com.lightningkite.lightningserver.metrics.MetricType
import com.lightningkite.lightningserver.metrics.MetricUnit
import com.lightningkite.lightningserver.metrics.Metrics
import kotlinx.serialization.KSerializer
import java.time.Duration

class MetricsCache(val wraps: Cache, metricsKeyName: String): Cache {
    val metricKey = MetricType("$metricsKeyName Wait Time", MetricUnit.Milliseconds)
    val countMetricKey = MetricType("$metricsKeyName Call Count", MetricUnit.Count)

    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? = Metrics.addPerformanceToSum(metricKey, countMetricKey) {
        wraps.get(key = key, serializer = serializer)
    }

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) = Metrics.addPerformanceToSum(metricKey, countMetricKey) {
        wraps.set(key = key, value = value, serializer = serializer, timeToLive = timeToLive)
    }

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?
    ): Boolean = Metrics.addPerformanceToSum(metricKey, countMetricKey) {
        wraps.setIfNotExists(key = key, value = value, serializer = serializer, timeToLive = timeToLive)
    }

    override suspend fun add(key: String, value: Int, timeToLive: Duration?) = Metrics.addPerformanceToSum(metricKey, countMetricKey) {
        wraps.add(key = key, value = value, timeToLive = timeToLive)
    }

    override suspend fun clear() = Metrics.addPerformanceToSum(metricKey, countMetricKey) {
        wraps.clear()
    }

    override suspend fun remove(key: String) = Metrics.addPerformanceToSum(metricKey, countMetricKey) {
        wraps.remove(key)
    }
}

fun Cache.metrics(metricsKeyName: String): MetricsCache = MetricsCache(this, metricsKeyName)