package com.lightningkite.lightningserver.ratelimit

import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.cache.Cache
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class RateLimitInterceptorTest {

    @Test
    fun `requests within limit are allowed`() {
        val rateLimitSettings = RateLimitSettings()
        val server = object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings())
            val rateLimiter = install(RateLimitInterceptor(cache, com.lightningkite.lightningserver.definition.Runtime.Constant(rateLimitSettings)))

            init { registerBasicMediaTypeCoders() }

            val endpoint = path.path("test").get bind rateLimiter.limit(
                HttpHandler { HttpResponse.plainText("OK") },
                requests = 5,
                window = 1.minutes,
            )
        }

        server.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                repeat(5) {
                    val response = server.endpoint.test()
                    assertEquals(HttpStatus.OK, response.status)
                    assertEquals("OK", response.body!!.text())
                }
            }
        }
    }

    @Test
    fun `request exceeding limit is rejected with 429`() {
        val rateLimitSettings = RateLimitSettings()
        val server = object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings())
            val rateLimiter = install(RateLimitInterceptor(cache, com.lightningkite.lightningserver.definition.Runtime.Constant(rateLimitSettings)))

            init { registerBasicMediaTypeCoders() }

            val endpoint = path.path("test").get bind rateLimiter.limit(
                HttpHandler { HttpResponse.plainText("OK") },
                requests = 3,
                window = 1.minutes,
            )
        }

        server.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                repeat(3) {
                    val response = server.endpoint.test()
                    assertEquals(HttpStatus.OK, response.status)
                }
                val rejected = server.endpoint.test()
                assertEquals(HttpStatus.TooManyRequests, rejected.status)
            }
        }
    }

    @Test
    fun `rate limit headers are added to responses`() {
        val rateLimitSettings = RateLimitSettings(headerPrefix = "X-RateLimit-")
        val server = object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings())
            val rateLimiter = install(RateLimitInterceptor(cache, com.lightningkite.lightningserver.definition.Runtime.Constant(rateLimitSettings)))

            init { registerBasicMediaTypeCoders() }

            val endpoint = path.path("test").get bind rateLimiter.limit(
                HttpHandler { HttpResponse.plainText("OK") },
                requests = 10,
                window = 1.minutes,
            )
        }

        server.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val response = server.endpoint.test()
                assertEquals(HttpStatus.OK, response.status)
                assertNotNull(response.headers["x-ratelimit-limit"])
                assertNotNull(response.headers["x-ratelimit-remaining"])
                assertNotNull(response.headers["x-ratelimit-reset"])
                assertEquals("10", response.headers["x-ratelimit-limit"]?.root)
                assertEquals("9", response.headers["x-ratelimit-remaining"]?.root)
            }
        }
    }

    @Test
    fun `rejected response includes Retry-After header`() {
        val rateLimitSettings = RateLimitSettings()
        val server = object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings())
            val rateLimiter = install(RateLimitInterceptor(cache, com.lightningkite.lightningserver.definition.Runtime.Constant(rateLimitSettings)))

            init { registerBasicMediaTypeCoders() }

            val endpoint = path.path("test").get bind rateLimiter.limit(
                HttpHandler { HttpResponse.plainText("OK") },
                requests = 1,
                window = 1.minutes,
            )
        }

        server.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                server.endpoint.test() // First request OK
                val rejected = server.endpoint.test() // Second request rejected
                assertEquals(HttpStatus.TooManyRequests, rejected.status)
                assertNotNull(rejected.headers[HttpHeader.RetryAfter.lowercase()])
            }
        }
    }

    @Test
    fun `disabled rate limiting passes all requests through`() {
        val rateLimitSettings = RateLimitSettings(enabled = false)
        val server = object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings())
            val rateLimiter = install(RateLimitInterceptor(cache, com.lightningkite.lightningserver.definition.Runtime.Constant(rateLimitSettings)))

            init { registerBasicMediaTypeCoders() }

            val endpoint = path.path("test").get bind rateLimiter.limit(
                HttpHandler { HttpResponse.plainText("OK") },
                requests = 1,
                window = 1.minutes,
            )
        }

        server.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                // Even after many requests, all should pass when disabled
                repeat(10) {
                    val response = server.endpoint.test()
                    assertEquals(HttpStatus.OK, response.status)
                }
            }
        }
    }

    @Test
    fun `endpoints without rate limit pass through`() {
        val rateLimitSettings = RateLimitSettings()
        val server = object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings())
            val rateLimiter = install(RateLimitInterceptor(cache, com.lightningkite.lightningserver.definition.Runtime.Constant(rateLimitSettings)))

            init { registerBasicMediaTypeCoders() }

            val endpoint = path.path("test").get bind HttpHandler {
                HttpResponse.plainText("OK")
            }
        }

        server.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                repeat(100) {
                    val response = server.endpoint.test()
                    assertEquals(HttpStatus.OK, response.status)
                    // No rate limit headers should be present
                }
            }
        }
    }

    @Test
    fun `default limit applies to unconfigured endpoints`() {
        val rateLimitSettings = RateLimitSettings(
            defaultLimit = RateLimitConfig(
                requests = 2,
                window = 1.minutes,
            )
        )
        val server = object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings())
            val rateLimiter = install(RateLimitInterceptor(cache, com.lightningkite.lightningserver.definition.Runtime.Constant(rateLimitSettings)))

            init { registerBasicMediaTypeCoders() }

            val endpoint = path.path("test").get bind HttpHandler {
                HttpResponse.plainText("OK")
            }
        }

        server.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                repeat(2) {
                    val response = server.endpoint.test()
                    assertEquals(HttpStatus.OK, response.status)
                }
                val rejected = server.endpoint.test()
                assertEquals(HttpStatus.TooManyRequests, rejected.status)
            }
        }
    }

    @Test
    fun `different endpoints have independent rate limits`() {
        val rateLimitSettings = RateLimitSettings()
        val server = object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings())
            val rateLimiter = install(RateLimitInterceptor(cache, com.lightningkite.lightningserver.definition.Runtime.Constant(rateLimitSettings)))

            init { registerBasicMediaTypeCoders() }

            val endpoint1 = path.path("test1").get bind rateLimiter.limit(
                HttpHandler { HttpResponse.plainText("1") },
                requests = 2,
                window = 1.minutes,
            )
            val endpoint2 = path.path("test2").get bind rateLimiter.limit(
                HttpHandler { HttpResponse.plainText("2") },
                requests = 2,
                window = 1.minutes,
            )
        }

        server.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                // Exhaust endpoint1's limit
                repeat(2) { server.endpoint1.test() }
                val rejected1 = server.endpoint1.test()
                assertEquals(HttpStatus.TooManyRequests, rejected1.status)

                // Endpoint2 should still work
                val response2 = server.endpoint2.test()
                assertEquals(HttpStatus.OK, response2.status)
            }
        }
    }

    @Test
    fun `shared scope merges rate limits across endpoints`() {
        val rateLimitSettings = RateLimitSettings()
        val server = object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings())
            val rateLimiter = install(RateLimitInterceptor(cache, com.lightningkite.lightningserver.definition.Runtime.Constant(rateLimitSettings)))

            init { registerBasicMediaTypeCoders() }

            val endpoint1 = path.path("a").get bind rateLimiter.limit(
                HttpHandler { HttpResponse.plainText("a") },
                requests = 3,
                window = 1.minutes,
                scope = "shared",
            )
            val endpoint2 = path.path("b").get bind rateLimiter.limit(
                HttpHandler { HttpResponse.plainText("b") },
                requests = 3,
                window = 1.minutes,
                scope = "shared",
            )
        }

        server.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                // Use 2 requests on endpoint1
                repeat(2) { server.endpoint1.test() }
                // Use 1 request on endpoint2 (total 3 in shared scope)
                server.endpoint2.test()
                // Both should now be rejected
                val rejected1 = server.endpoint1.test()
                assertEquals(HttpStatus.TooManyRequests, rejected1.status)
                val rejected2 = server.endpoint2.test()
                assertEquals(HttpStatus.TooManyRequests, rejected2.status)
            }
        }
    }

    @Test
    fun `remaining count decreases with each request`() {
        val rateLimitSettings = RateLimitSettings()
        val server = object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings())
            val rateLimiter = install(RateLimitInterceptor(cache, com.lightningkite.lightningserver.definition.Runtime.Constant(rateLimitSettings)))

            init { registerBasicMediaTypeCoders() }

            val endpoint = path.path("test").get bind rateLimiter.limit(
                HttpHandler { HttpResponse.plainText("OK") },
                requests = 5,
                window = 1.minutes,
            )
        }

        server.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                for (i in 0 until 5) {
                    val response = server.endpoint.test()
                    val remaining = response.headers["x-ratelimit-remaining"]?.root?.toInt()
                    assertEquals(4 - i, remaining)
                }
            }
        }
    }

    @Test
    fun `global key strategy shares limit across all IPs`() {
        val rateLimitSettings = RateLimitSettings()
        val server = object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings())
            val rateLimiter = install(RateLimitInterceptor(cache, com.lightningkite.lightningserver.definition.Runtime.Constant(rateLimitSettings)))

            init { registerBasicMediaTypeCoders() }

            val endpoint = path.path("test").get bind rateLimiter.limit(
                HttpHandler { HttpResponse.plainText("OK") },
                requests = 2,
                window = 1.minutes,
                keyStrategy = KeyStrategy.GLOBAL,
            )
        }

        server.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                // Requests from different IPs share the same counter
                server.endpoint.test(sourceIp = "1.1.1.1")
                server.endpoint.test(sourceIp = "2.2.2.2")
                val rejected = server.endpoint.test(sourceIp = "3.3.3.3")
                assertEquals(HttpStatus.TooManyRequests, rejected.status)
            }
        }
    }

    @Test
    fun `IP key strategy separates limits by IP`() {
        val rateLimitSettings = RateLimitSettings()
        val server = object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings())
            val rateLimiter = install(RateLimitInterceptor(cache, com.lightningkite.lightningserver.definition.Runtime.Constant(rateLimitSettings)))

            init { registerBasicMediaTypeCoders() }

            val endpoint = path.path("test").get bind rateLimiter.limit(
                HttpHandler { HttpResponse.plainText("OK") },
                requests = 1,
                window = 1.minutes,
                keyStrategy = KeyStrategy.IP,
            )
        }

        server.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                // First IP can make 1 request
                val r1 = server.endpoint.test(sourceIp = "1.1.1.1")
                assertEquals(HttpStatus.OK, r1.status)

                // First IP is now rate limited
                val r2 = server.endpoint.test(sourceIp = "1.1.1.1")
                assertEquals(HttpStatus.TooManyRequests, r2.status)

                // Second IP can still make a request
                val r3 = server.endpoint.test(sourceIp = "2.2.2.2")
                assertEquals(HttpStatus.OK, r3.status)
            }
        }
    }
}
