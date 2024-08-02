package com.lightningkite.lightningserver.security

import com.lightningkite.default
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.http.HttpRequest
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Assert.*
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RateLimiterTest {
    private suspend fun assertSuccessesForLimiter(expected: Int, limiter: RateLimiter, delayBetween: Duration, requestTime: Duration) {
        var time: Instant = Instant.fromEpochMilliseconds(0)
        val clock = object : Clock {
            override fun now(): Instant = time
        }
        val dummy = HttpRequest(
            endpoint = HttpEndpoint("", HttpMethod.GET),
        )
        try {
            Clock.default = clock
            var successRequests = 0
            for (i in 0..1000) {
                try {
                    time += delayBetween
                    limiter.gate(dummy, null, "A") { time += requestTime }
                    successRequests++
                } catch (e: HttpStatusException) {
                    println(e.data)
                    break
                }
            }
            println("Successes: $successRequests")
            kotlin.test.assertEquals(expected, successRequests)
        } finally {
            Clock.default = Clock.System
        }
    }
    @Test
    fun simpleTest() {
        runBlocking {
            TestSettings
            val c = LocalCache()

            assertSuccessesForLimiter(
                expected = 23,
                limiter = RateLimiter(
                    cache = { c },
                    leeway = 200.seconds,
                    perAuth = { _, _ -> 0.1f },
                    virtualDurationModifier = { r, it -> it },
                    borrowTime = { _, _ -> 10.seconds },
//                    log = ::println
                ),
                delayBetween = 0.seconds,
                requestTime = 1.seconds
            )
            c.clear()

            // perAuth is the key to controlling cost; higher values allow more requests
            assertSuccessesForLimiter(
                expected = 51,
                limiter = RateLimiter(
                    cache = { c },
                    leeway = 200.seconds,
                    perAuth = { _, _ -> 0.2f },
                    virtualDurationModifier = { r, it -> it },
                    borrowTime = { _, _ -> 10.seconds },
                    log = ::println
                ),
                delayBetween = 0.seconds,
                requestTime = 1.seconds
            )
            c.clear()

            // Borrow time does not affect the number of successive calls, only concurrency
            assertSuccessesForLimiter(
                expected = 23,
                limiter = RateLimiter(
                    cache = { c },
                    leeway = 200.seconds,
                    perAuth = { _, _ -> 0.1f },
                    virtualDurationModifier = { r, it -> it },
                    borrowTime = { _, _ -> 100.seconds },
//                    log = ::println
                ),
                delayBetween = 0.seconds,
                requestTime = 1.seconds
            )
            c.clear()

            // With no artificial duration modification, many short requests are approximately equivalent to a few long ones
            assertSuccessesForLimiter(
                expected = 89,
                limiter = RateLimiter(
                    cache = { c },
                    leeway = 200.seconds,
                    perAuth = { _, _ -> 0.1f },
                    virtualDurationModifier = { r, it -> it },
                    borrowTime = { _, _ -> 10.seconds },
//                    log = ::println
                ),
                delayBetween = 0.seconds,
                requestTime = 1.seconds / 4
            )
            c.clear()

            // With no artificial duration modification, many short requests are approximately equivalent to a few long ones
            assertSuccessesForLimiter(
                expected = 5,
                limiter = RateLimiter(
                    cache = { c },
                    leeway = 200.seconds,
                    perAuth = { _, _ -> 0.1f },
                    virtualDurationModifier = { r, it -> it },
                    borrowTime = { _, _ -> 10.seconds },
//                    log = ::println
                ),
                delayBetween = 0.seconds,
                requestTime = 5.seconds
            )
            c.clear()

            // virtualDurationModifier allows you to make requests artificially "take more time"
            // This allows you to make users pay for your load balancer
            assertSuccessesForLimiter(
                expected = 11,
                limiter = RateLimiter(
                    cache = { c },
                    leeway = 200.seconds,
                    perAuth = { _, _ -> 0.1f },
                    virtualDurationModifier = { r, it -> it + 1.seconds },
                    borrowTime = { _, _ -> 10.seconds },
                    log = ::println
                ),
                delayBetween = 0.seconds,
                requestTime = 1.seconds
            )
            c.clear()
        }
    }

    @Test
    fun test() {
        runBlocking {
            TestSettings
            val c = LocalCache()
            val limiter =
                RateLimiter(
                    cache = { c },
                    perAuth = { _, _ -> 0.5f },
                    virtualDurationModifier = { r, it -> it },
                    borrowTime = { _, _ -> 10.seconds })
            var time: Instant = Instant.fromEpochMilliseconds(0)
            val clock = object : Clock {
                override fun now(): Instant = time
            }
            try {
                Clock.default = clock

                val dummy = HttpRequest(
                    endpoint = HttpEndpoint("", HttpMethod.GET),
                )
                var successRequests = 0

                for (i in 0..1000) {
                    try {
                        time += 2.seconds
                        limiter.gate(dummy, null, "A") { time += 4.seconds }
                        successRequests++
                    } catch (e: HttpStatusException) {
                        break
                    }
                }
                try {
                    repeat(10) {
                        time += 2.seconds
                        limiter.gate(dummy, null, "A") { time += 4.seconds }
                        successRequests++
                    }
                    fail()
                } catch (e: HttpStatusException) {
                    // OK!
                }
                time += 1.minutes
                for (i in 0..1000) {
                    try {
                        time += 2.seconds
                        limiter.gate(dummy, null, "A") { time += 4.seconds }
                        successRequests++
                    } catch (e: HttpStatusException) {
                        break
                    }
                }
                try {
                    repeat(10) {
                        time += 2.seconds
                        limiter.gate(dummy, null, "A") { time += 4.seconds }
                        successRequests++
                    }
                    fail()
                } catch (e: HttpStatusException) {
                    // OK!
                }

                println("successRequests: $successRequests")

            } finally {
                Clock.default = Clock.System
            }
        }
    }
}