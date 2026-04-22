// by Claude - demo load test targeting the demo server's typed endpoints
package com.lightningkite.lightningserver.demo

import com.lightningkite.lightningserver.demo.endpoints.*
import com.lightningkite.lightningserver.loadtest.Scenario
import com.lightningkite.lightningserver.loadtest.loadTest
import kotlin.time.Duration.Companion.seconds

/**
 * Example load test against the demo server's typed API endpoints.
 *
 * Usage:
 *   1. Start the demo server: ./gradlew :demo:run --args="serve"
 *   2. Run this load test from a separate terminal
 */
suspend fun main() {
    loadTest(
        server = Server,
        baseUrl = "http://localhost:8080",
        virtualUsers = 50,
        rampUp = 5.seconds,
        sustain = 30.seconds,
        scenarios = listOf(
            Scenario("calculator", weight = 3) {
                call(
                    TypedApiExamplesEndpoints.calculator,
                    CalculatorRequest(a = 10.0, b = 5.0, operation = "+")
                )
                call(
                    TypedApiExamplesEndpoints.calculator,
                    CalculatorRequest(a = 100.0, b = 7.0, operation = "/")
                )
            },
            Scenario("search", weight = 2) {
                call(
                    TypedApiExamplesEndpoints.search,
                    SearchRequest(query = "lightning", maxResults = 10)
                )
            },
            Scenario("transform", weight = 1) {
                call(
                    TypedApiExamplesEndpoints.transform,
                    TransformRequest(text = "hello world", type = TransformType.UPPERCASE)
                )
                call(
                    TypedApiExamplesEndpoints.transform,
                    TransformRequest(text = "LOUD NOISES", type = TransformType.LOWERCASE)
                )
            },
        )
    )
}
