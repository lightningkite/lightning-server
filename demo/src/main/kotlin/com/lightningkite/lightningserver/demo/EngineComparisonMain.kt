// by Claude - automated engine comparison load test
package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.demo.endpoints.*
import com.lightningkite.lightningserver.loadtest.LoadTestSummary
import com.lightningkite.lightningserver.loadtest.Scenario
import com.lightningkite.lightningserver.loadtest.loadTest
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.time.Duration.Companion.seconds

/**
 * Launches each Lightning Server engine in a separate JVM process, runs the same
 * load test against each, and prints a side-by-side comparison table.
 *
 * Each engine runs in its own process to avoid singleton conflicts from calling
 * Server.build() multiple times. The classpath is inherited from this process.
 *
 * This measures the framework+engine overhead by using trivial typed endpoints
 * (no I/O), so the measured time is routing + serialization + engine HTTP handling.
 *
 * Usage:
 *   ./gradlew :demo:engineComparison
 */
suspend fun main(args: Array<String>) {
    val baseUrl = "http://localhost:8080"
    val results = mutableListOf<Pair<String, LoadTestSummary>>()

    val scenarios = listOf(
        Scenario("calculator", weight = 3) {
            call(
                TypedApiExamplesEndpoints.calculator,
                CalculatorRequest(a = 10.0, b = 5.0, operation = "+")
            )
        },
        Scenario("search", weight = 2) {
            call(
                TypedApiExamplesEndpoints.search,
                SearchRequest(query = "lightning", maxResults = 10)
            )
        },
    )

    data class EngineConfig(
        val name: String,
        val startArg: String,
    )

    val engines = listOf(
        EngineConfig("Ktor+Netty", "serve"),
        EngineConfig("Raw Netty", "serveNetty"),
        EngineConfig("JDK HttpServer", "serveJdk"),
    )

    val classpath = System.getProperty("java.class.path")
    val javaHome = System.getProperty("java.home")
    val javaBin = "$javaHome/bin/java"
    val workingDir = File(System.getProperty("user.dir"))

    for (engine in engines) {
        println()
        println("=".repeat(80))
        println("  ENGINE: ${engine.name}")
        println("=".repeat(80))

        // Launch the server in a separate JVM process to avoid singleton conflicts
        val process = ProcessBuilder(
            javaBin, "-cp", classpath,
            "com.lightningkite.lightningserver.demo.MainKt",
            engine.startArg
        )
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()

        // Drain stdout in background to prevent process from blocking on full buffer
        val outputDrain = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                println("  [${engine.name}] $line")
            }
        }.apply { isDaemon = true; start() }

        try {
            if (!waitForServer(baseUrl, timeoutSeconds = 30)) {
                println("  SKIPPED - server did not start within 30s")
                continue
            }
            println("  Server ready, warming up...")

            // Warmup pass (JIT, class loading)
            loadTest(
                server = Server,
                baseUrl = baseUrl,
                virtualUsers = 5,
                rampUp = 0.seconds,
                sustain = 3.seconds,
                scenarios = scenarios,
            )
            println()
            println("  Warmup complete, starting measured run...")

            // Measured pass
            val summary = loadTest(
                server = Server,
                baseUrl = baseUrl,
                virtualUsers = 10,
                rampUp = 2.seconds,
                sustain = 8.seconds,
                scenarios = scenarios,
            )
            results.add(engine.name to summary)
        } finally {
            process.destroyForcibly()
            process.waitFor()
            Thread.sleep(1000) // Let port release
        }
    }

    // Print comparison table
    println()
    println("=".repeat(80))
    println("  ENGINE COMPARISON SUMMARY")
    println("=".repeat(80))
    println()

    if (results.isEmpty()) {
        println("No results collected.")
        return
    }

    println(String.format("%-16s %10s %10s %10s %10s %10s", "Engine", "Requests", "Errors", "Avg(ms)", "Max(ms)", "Req/s"))
    println("-".repeat(80))
    for ((name, summary) in results) {
        val avgMs = summary.endpoints.map { it.avgMs }.average()
        val maxMs = summary.endpoints.maxOfOrNull { it.maxMs } ?: 0.0
        println(String.format("%-16s %10d %10d %10.1f %10.1f %10.1f", name, summary.totalRequests, summary.totalErrors, avgMs, maxMs, summary.requestsPerSecond))
    }
    println()

    // Per-endpoint breakdown
    val allPaths = results.flatMap { it.second.endpoints.map { e -> "${e.method} ${e.path}" } }.distinct().sorted()
    for (endpointKey in allPaths) {
        println("  $endpointKey:")
        for ((name, summary) in results) {
            val ep = summary.endpoints.find { "${it.method} ${it.path}" == endpointKey }
            if (ep != null) {
                println(String.format("    %-16s  avg=%.1fms  min=%.1fms  max=%.1fms  %d req", name, ep.avgMs, ep.minMs, ep.maxMs, ep.requests))
            }
        }
    }
    println()
}

private fun waitForServer(baseUrl: String, timeoutSeconds: Int): Boolean {
    val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
    while (System.currentTimeMillis() < deadline) {
        try {
            val conn = URI.create("$baseUrl/").toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.requestMethod = "GET"
            if (conn.responseCode in 200..499) return true
        } catch (_: Exception) {
            // not ready yet
        }
        Thread.sleep(500)
    }
    return false
}
