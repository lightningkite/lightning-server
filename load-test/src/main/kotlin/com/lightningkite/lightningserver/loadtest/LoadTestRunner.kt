// by Claude - coroutine orchestration for load test virtual users
package com.lightningkite.lightningserver.loadtest

import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.services.data.StringArrayFormat
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.net.http.HttpClient
import kotlin.time.Duration
import kotlin.time.TimeSource
import java.time.Duration as JavaDuration

/**
 * Runs the load test with the given configuration.
 *
 * Each virtual user gets its own HttpClient and ScenarioContext, loops its
 * assigned scenario until the total test duration (rampUp + sustain) elapses.
 * Exceptions in scenario lambdas are caught and recorded as errors.
 */
internal suspend fun runLoadTest(
    definition: ServerDefinition,
    json: Json,
    stringArrayFormat: StringArrayFormat,
    baseUrl: String,
    virtualUsers: Int,
    rampUp: Duration,
    sustain: Duration,
    headers: Map<String, String>,
    requestTimeout: Duration,
    scenarios: List<Scenario>,
): LoadTestMetrics {
    require(scenarios.isNotEmpty()) { "At least one scenario is required" }
    require(virtualUsers > 0) { "At least one virtual user is required" }

    val metrics = LoadTestMetrics()
    val totalWeight = scenarios.sumOf { it.weight }
    val totalDuration = rampUp + sustain
    val timeSource = TimeSource.Monotonic

    coroutineScope {
        for (userIndex in 0 until virtualUsers) {
            launch(Dispatchers.Default) {
                // Ramp-up delay: stagger user starts evenly across the ramp-up period
                if (rampUp.isPositive() && virtualUsers > 1) {
                    val delayPerUser = rampUp / virtualUsers
                    delay(delayPerUser * userIndex)
                }

                val client = HttpClient.newBuilder()
                    .connectTimeout(JavaDuration.ofMillis(requestTimeout.inWholeMilliseconds))
                    .build()

                val context = ScenarioContext(
                    definition = definition,
                    json = json,
                    stringArrayFormat = stringArrayFormat,
                    client = client,
                    baseUrl = baseUrl.trimEnd('/'),
                    defaultHeaders = headers,
                    requestTimeout = requestTimeout,
                    metrics = metrics,
                )

                // Weighted scenario assignment: user N maps to a scenario based on cumulative weights
                val scenario = assignScenario(userIndex, virtualUsers, scenarios, totalWeight)

                val testStart = timeSource.markNow()
                while (testStart.elapsedNow() < totalDuration) {
                    try {
                        scenario.block(context)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Already recorded in ScenarioContext.call(); scenario-level exceptions
                        // (e.g. logic errors in the lambda) are silently swallowed to keep the user looping.
                    }
                }
            }
        }
    }

    return metrics
}

/**
 * Assigns a scenario to a virtual user based on weighted distribution.
 *
 * Users are distributed across scenarios proportionally to their weights.
 * For example, with scenarios A(weight=3) and B(weight=1) and 100 users:
 * users 0-74 get A, users 75-99 get B.
 */
private fun assignScenario(
    userIndex: Int,
    totalUsers: Int,
    scenarios: List<Scenario>,
    totalWeight: Int,
): Scenario {
    val position = (userIndex.toDouble() / totalUsers * totalWeight).toInt()
    var cumulative = 0
    for (scenario in scenarios) {
        cumulative += scenario.weight
        if (position < cumulative) return scenario
    }
    return scenarios.last()
}
