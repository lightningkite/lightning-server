package com.lightningkite.lightningserver.guide.samples

// region ratelimit-imports
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.cors.*
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.ratelimit.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.typed.MetaEndpoints
import com.lightningkite.lightningserver.typed.sdk.module
import com.lightningkite.lightningserver.websockets.MultiplexWebSocketHandler
import com.lightningkite.services.cache.*
import com.lightningkite.services.database.*
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Instant
// endregion ratelimit-imports

// region ratelimit-server
object LimitedServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val cors = setting("cors", CorsSettings())
    // Nullable, so `"rateLimit": null` in settings.json turns limiting off.
    val rateLimit = setting<RateLimitSettings?>("rateLimit", RateLimitSettings())

    val meta = path.path("meta") module MetaEndpoints("com.example", cache)
    val multiplex = path.path("multiplex") bind MultiplexWebSocketHandler()

    val corsInterceptor = install(CorsInterceptor(cors))
    val rateLimiter = install(
        RateLimitInterceptor(
            settings = rateLimit,
            cache = cache,
            exempt = listOf(meta.bulk),
            exemptSockets = listOf(multiplex),
        ) { request ->
            RequestLimits(key = "ip:${request.sourceIp}")
        }
    )

    val hello = path.path("hello").get bind HttpHandler { HttpResponse.plainText("Hello!") }
}
// endregion ratelimit-server

// region ratelimit-test
fun rateLimitTest() {
    val frozen = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_000_000)
    }
    LimitedServer.testBlocking(settings = {}, clock = { frozen }) {
        val first = LimitedServer.hello.test()
        assertEquals(HttpStatus.OK, first.status)
        assertEquals("ip:local", first.headers["X-RateLimit-Identity"]?.root)

        // With the clock frozen, every request spends budget and none is earned back.
        val responses = (1..200).map { LimitedServer.hello.test() }
        val rejected = responses.first { it.status == HttpStatus.TooManyRequests }
        assertNotNull(rejected.headers[HttpHeader.RetryAfter])
    }
}
// endregion ratelimit-test

// region ratelimit-keys
object PerUserLimitedServer : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())
    val rateLimit = setting<RateLimitSettings?>("rateLimit", RateLimitSettings())

    val search = path.path("search").get bind HttpHandler { HttpResponse.plainText("results") }

    val rateLimiter = install(RateLimitInterceptor(rateLimit, cache) { request ->
        val auth = request[Authentication.CacheKey]
        val handler = (request as? HttpRequest<*>)?.path?.tryResolve()?.value
        RequestLimits(
            key = auth?.let { "user:${it.principalName}:${it.rawId}" } ?: "ip:${request.sourceIp}",
            multiplier = if (handler === search) 30.0 else 10.0,
        )
    })
}
// endregion ratelimit-keys

fun perUserRateLimitTest() = PerUserLimitedServer.testBlocking(settings = {}) {
    val response = PerUserLimitedServer.search.test(sourceIp = "203.0.113.7")
    assertEquals(HttpStatus.OK, response.status)
    assertEquals("ip:203.0.113.7", response.headers["X-RateLimit-Identity"]?.root)
}

// region ratelimit-disable
fun rateLimitOffTest() = LimitedServer.testBlocking(settings = { rateLimit set null }) {
    val response = LimitedServer.hello.test()
    assertNull(response.headers["X-RateLimit-Identity"])
}
// endregion ratelimit-disable
