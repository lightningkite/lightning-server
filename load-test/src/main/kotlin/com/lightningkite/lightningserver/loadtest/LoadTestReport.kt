// by Claude - console report with summary table and ASCII latency histogram
package com.lightningkite.lightningserver.loadtest

import kotlin.time.Duration

/**
 * Prints load test results to stdout: summary table + per-endpoint latency histogram.
 */
internal fun printReport(metrics: LoadTestMetrics, totalDuration: Duration) {
    val entries = metrics.endpoints.entries.sortedBy { "${it.key.method} ${it.key.path}" }
    if (entries.isEmpty()) {
        println("No requests were recorded.")
        return
    }

    val totalSeconds = totalDuration.inWholeMilliseconds / 1000.0

    println()
    println("=" .repeat(100))
    println("  LOAD TEST RESULTS")
    println("=".repeat(100))
    println()

    // Summary table
    val header = String.format(
        "%-7s %-40s %8s %8s %8s %8s %8s %8s",
        "Method", "Path", "Requests", "Errors", "Avg(ms)", "Min(ms)", "Max(ms)", "Req/s"
    )
    println(header)
    println("-".repeat(100))

    var totalRequests = 0L
    var totalErrors = 0L
    var hasSlowEndpoint = false

    for ((key, m) in entries) {
        val requests = m.totalRequests.get()
        val errors = m.errorCount.get()
        val avgMs = if (requests > 0) m.totalDurationNanos.get() / requests / 1_000_000.0 else 0.0
        val minMs = if (m.minNanos.get() == Long.MAX_VALUE) 0.0 else m.minNanos.get() / 1_000_000.0
        val maxMs = m.maxNanos.get() / 1_000_000.0
        val rps = if (totalSeconds > 0) requests / totalSeconds else 0.0

        if (avgMs > 500) hasSlowEndpoint = true
        totalRequests += requests
        totalErrors += errors

        println(
            String.format(
                "%-7s %-40s %8d %8d %8.1f %8.1f %8.1f %8.1f",
                key.method, key.path.take(40), requests, errors, avgMs, minMs, maxMs, rps
            )
        )
    }

    println("-".repeat(100))
    val overallRps = if (totalSeconds > 0) totalRequests / totalSeconds else 0.0
    println(
        String.format(
            "%-48s %8d %8d %48s %8.1f",
            "TOTAL", totalRequests, totalErrors, "", overallRps
        )
    )
    println()

    // Per-endpoint latency histogram
    for ((key, m) in entries) {
        val requests = m.totalRequests.get()
        if (requests == 0L) continue

        println("  ${key.method} ${key.path}")
        val maxBucketCount = (0 until EndpointMetrics.BUCKET_COUNT).maxOf { m.histogram.get(it) }
        if (maxBucketCount == 0L) continue

        val barWidth = 40
        for (i in 0 until EndpointMetrics.BUCKET_COUNT) {
            val count = m.histogram.get(i)
            if (count == 0L) continue
            val bar = "█".repeat(((count.toDouble() / maxBucketCount) * barWidth).toInt().coerceAtLeast(1))
            val pct = count * 100.0 / requests
            println(
                String.format("    %-6s │ %s %d (%.1f%%)", EndpointMetrics.BUCKET_LABELS[i], bar, count, pct)
            )
        }
        println()
    }

    // Warnings
    if (hasSlowEndpoint) {
        println("⚠ WARNING: One or more endpoints have average client-side latency > 500ms.")
        println("  Check server-side OTel traces for detailed analysis.")
        println()
    }
    if (totalErrors > 0) {
        val errorRate = totalErrors * 100.0 / totalRequests
        println("⚠ WARNING: ${String.format("%.1f", errorRate)}% error rate ($totalErrors / $totalRequests requests).")
        println()
    }
}
