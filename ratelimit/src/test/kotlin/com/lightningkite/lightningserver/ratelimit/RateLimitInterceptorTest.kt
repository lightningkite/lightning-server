package com.lightningkite.lightningserver.ratelimit

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.MapCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RateLimiterTest {

    object TestServer : ServerBuilder() {
        val limiter = setting("limiter", RateLimitSettings())
        val cache = setting("cache", Cache.Settings("ram"))
    }

    var time: Instant = Instant.fromEpochMilliseconds(0)
    val clock = object : Clock {
        override fun now(): Instant = time
    }

    @BeforeTest
    fun resetClock() {
        time = Instant.fromEpochMilliseconds(0)
    }

    context(runtime: ServerRuntime)
    private suspend fun assertSuccessesForLimiter(
        expected: Int,
        limiter: RateLimitInterceptor,
        delayBetween: Duration,
        requestTime: Duration,
    ) {

        val dummy = HttpRequest(
            RawHttpEndpoint("", method = HttpMethod.GET),
            queryParameters = QueryParameters.EMPTY,
            headers = HttpHeaders(),
            domain = "Domain",
            protocol = "Http",
            sourceIp = "localhost",
            requestId = generateRequestId(),
            body = null,
        )
        var successRequests = 0
        for (i in 0..1000) {
            try {
                time += delayBetween
                limiter.intercept(dummy) {
                    time += requestTime
                    HttpResponse()
                }
                successRequests++
            } catch (e: HttpStatusException) {
                println(e.data)
                break
            }
        }
        println("Successes: $successRequests")
        kotlin.test.assertEquals(expected, successRequests)

    }

    @Test
    fun simpleTest() {
        TestServer.test(
            {},
            { clock }
        ) {
            runBlocking {

                RateLimitInterceptor(
                    limiter,
                    cache,
                    { null }
                )

                assertSuccessesForLimiter(
                    expected = 23,
                    limiter = RateLimitInterceptor(
                        limiter,
                        cache,
                        {
                            RequestLimits(
                                key = "simpleTest",
                                leeway = 200.seconds,
                                borrowTime = 10.seconds,
                                multiplier = 10.0,
                                overhead = 0.seconds
                            )
                        }
                    ),
                    delayBetween = 0.seconds,
                    requestTime = 1.seconds
                )
                (cache() as? MapCache)?.clear()

                // multiplier is the key to controlling cost; lower values allow more requests
                assertSuccessesForLimiter(
                    expected = 51,
                    limiter = RateLimitInterceptor(
                        limiter,
                        cache,
                        {
                            RequestLimits(
                                key = "simpleTest",
                                leeway = 200.seconds,
                                borrowTime = 10.seconds,
                                multiplier = 5.0,
                                overhead = 0.seconds
                            )
                        }
                    ),
                    delayBetween = 0.seconds,
                    requestTime = 1.seconds
                )
                (cache() as? MapCache)?.clear()

                // Borrow time does not affect the number of successive calls, only concurrency
                assertSuccessesForLimiter(
                    expected = 23,
                    limiter = RateLimitInterceptor(
                        limiter,
                        cache,
                        {
                            RequestLimits(
                                key = "simpleTest",
                                leeway = 200.seconds,
                                borrowTime = 100.seconds,
                                multiplier = 10.0,
                                overhead = 0.seconds
                            )
                        }
                    ),
                    delayBetween = 0.seconds,
                    requestTime = 1.seconds
                )

                (cache() as? MapCache)?.clear()

                // With no artificial duration modification, many short requests are approximately equivalent to a few long ones
                assertSuccessesForLimiter(
                    expected = 89,
                    limiter = RateLimitInterceptor(
                        limiter,
                        cache,
                        {
                            RequestLimits(
                                key = "simpleTest",
                                leeway = 200.seconds,
                                borrowTime = 10.seconds,
                                multiplier = 10.0,
                                overhead = 0.seconds
                            )
                        }
                    ),
                    delayBetween = 0.seconds,
                    requestTime = 1.seconds / 4
                )

                (cache() as? MapCache)?.clear()

                // With no artificial duration modification, many short requests are approximately equivalent to a few long ones
                assertSuccessesForLimiter(
                    expected = 5,
                    limiter = RateLimitInterceptor(
                        limiter,
                        cache,
                        {
                            RequestLimits(
                                key = "simpleTest",
                                leeway = 200.seconds,
                                borrowTime = 10.seconds,
                                multiplier = 10.0,
                                overhead = 0.seconds
                            )
                        }
                    ),
                    delayBetween = 0.seconds,
                    requestTime = 5.seconds
                )

                (cache() as? MapCache)?.clear()

                // overhead allows you to make requests artificially "take more time"
                // This allows you to make users pay for your load balancer
                assertSuccessesForLimiter(
                    expected = 11,
                    limiter = RateLimitInterceptor(
                        limiter,
                        cache,
                        {
                            RequestLimits(
                                key = "simpleTest",
                                leeway = 200.seconds,
                                borrowTime = 10.seconds,
                                multiplier = 10.0,
                                overhead = 1.seconds
                            )
                        }
                    ),
                    delayBetween = 0.seconds,
                    requestTime = 1.seconds
                )

                (cache() as? MapCache)?.clear()
            }
        }
    }

    @Test
    fun test() {

        TestServer.test(
            {},
            { clock }
        ) {
            runBlocking {

                val limiter = RateLimitInterceptor(
                    limiter,
                    cache,
                    {
                        RequestLimits(
                            key = "test",
                            leeway = 200.seconds,
                            borrowTime = 10.seconds,
                            multiplier = 2.0,
                            overhead = 0.seconds
                        )
                    }
                )

                val dummy = HttpRequest(
                    RawHttpEndpoint("", method = HttpMethod.GET),
                    queryParameters = QueryParameters.EMPTY,
                    headers = HttpHeaders(),
                    domain = "Domain",
                    protocol = "Http",
                    sourceIp = "localhost",
                    requestId = generateRequestId(),
                    body = null,
                )
                var successRequests = 0

                for (i in 0..1000) {
                    try {
                        time += 2.seconds
                        limiter.intercept(dummy) {
                            time += 4.seconds
                            HttpResponse()
                        }
                        successRequests++
                    } catch (e: HttpStatusException) {
                        break
                    }
                }
                try {
                    repeat(10) {
                        time += 2.seconds
                        limiter.intercept(dummy) {
                            time += 4.seconds
                            HttpResponse()
                        }
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
                        limiter.intercept(dummy) {
                            time += 4.seconds
                            HttpResponse()
                        }
                        successRequests++
                    } catch (e: HttpStatusException) {
                        break
                    }
                }
                try {
                    repeat(10) {
                        time += 2.seconds
                        limiter.intercept(dummy) {
                            time += 4.seconds
                            HttpResponse()
                        }
                        successRequests++
                    }
                    fail()
                } catch (e: HttpStatusException) {
                    // OK!
                }

                println("successRequests: $successRequests")

            }
        }
    }
}