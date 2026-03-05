// by Claude - lock-free metrics collection for load testing
package com.lightningkite.lightningserver.loadtest

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Lock-free metrics collection for load test results.
 *
 * Collects per-endpoint timing and error data using atomic operations,
 * safe for concurrent writes from many virtual user coroutines.
 */
internal class LoadTestMetrics {
    internal val endpoints = ConcurrentHashMap<EndpointKey, EndpointMetrics>()

    fun record(method: String, path: String, durationNanos: Long, isError: Boolean) {
        val key = EndpointKey(method, path)
        val metrics = endpoints.getOrPut(key) { EndpointMetrics() }
        metrics.record(durationNanos, isError)
    }

    fun recordException(method: String, path: String) {
        val key = EndpointKey(method, path)
        val metrics = endpoints.getOrPut(key) { EndpointMetrics() }
        metrics.totalRequests.incrementAndGet()
        metrics.errorCount.incrementAndGet()
    }
}

internal data class EndpointKey(val method: String, val path: String)

internal class EndpointMetrics {
    val totalRequests = AtomicLong(0)
    val errorCount = AtomicLong(0)
    val totalDurationNanos = AtomicLong(0)
    val minNanos = AtomicLong(Long.MAX_VALUE)
    val maxNanos = AtomicLong(0)

    // Latency histogram buckets: <1ms, <5ms, <10ms, <25ms, <50ms, <100ms, <250ms, <500ms, <1s, >=1s
    val histogram = AtomicLongArray(BUCKET_COUNT)

    fun record(durationNanos: Long, isError: Boolean) {
        totalRequests.incrementAndGet()
        if (isError) errorCount.incrementAndGet()
        totalDurationNanos.addAndGet(durationNanos)

        // Update min
        var current = minNanos.get()
        while (durationNanos < current) {
            if (minNanos.compareAndSet(current, durationNanos)) break
            current = minNanos.get()
        }

        // Update max
        current = maxNanos.get()
        while (durationNanos > current) {
            if (maxNanos.compareAndSet(current, durationNanos)) break
            current = maxNanos.get()
        }

        // Histogram
        val bucket = bucketFor(durationNanos)
        histogram.incrementAndGet(bucket)
    }

    companion object {
        const val BUCKET_COUNT = 10
        val BUCKET_THRESHOLDS_NS = longArrayOf(
            1_000_000L,      // <1ms
            5_000_000L,      // <5ms
            10_000_000L,     // <10ms
            25_000_000L,     // <25ms
            50_000_000L,     // <50ms
            100_000_000L,    // <100ms
            250_000_000L,    // <250ms
            500_000_000L,    // <500ms
            1_000_000_000L,  // <1s
        )
        val BUCKET_LABELS = arrayOf(
            "<1ms", "<5ms", "<10ms", "<25ms", "<50ms",
            "<100ms", "<250ms", "<500ms", "<1s", ">=1s"
        )

        fun bucketFor(durationNanos: Long): Int {
            for (i in BUCKET_THRESHOLDS_NS.indices) {
                if (durationNanos < BUCKET_THRESHOLDS_NS[i]) return i
            }
            return BUCKET_COUNT - 1
        }
    }
}
