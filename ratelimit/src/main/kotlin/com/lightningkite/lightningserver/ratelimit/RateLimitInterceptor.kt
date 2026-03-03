package com.lightningkite.lightningserver.ratelimit

import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.get
import java.util.IdentityHashMap
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * HTTP interceptor that enforces per-endpoint rate limiting using a sliding window algorithm.
 *
 * Rate limits can be configured per-endpoint using [limit] or globally via [RateLimitSettings.defaultLimit].
 * Rate limit counters are stored in the [Cache] service abstraction, enabling distributed rate limiting
 * across multiple server instances.
 *
 * ## Usage
 * ```kotlin
 * object Server : ServerBuilder() {
 *     val cache = setting("cache", Cache.Settings())
 *     val rateLimitSettings = setting("rateLimit", RateLimitSettings())
 *     val rateLimiter = install(RateLimitInterceptor(cache, rateLimitSettings))
 *
 *     val endpoint = path.path("api").post bind rateLimiter.limit(
 *         HttpHandler { HttpResponse.plainText("OK") },
 *         requests = 10,
 *         window = 1.minutes
 *     )
 * }
 * ```
 *
 * @param cache Runtime providing the cache service for storing rate limit counters
 * @param settings Runtime providing the rate limit configuration
 */
public class RateLimitInterceptor(
    private val cache: Runtime<Cache>,
    private val settings: Runtime<RateLimitSettings>,
) : HttpInterceptor {

    override val name: String = "RateLimit"

    private val configs = IdentityHashMap<HttpHandler<*>, RateLimitConfig>()

    /**
     * Registers a rate limit configuration for a handler and returns the handler unchanged.
     *
     * @param handler The handler to rate limit
     * @param config The rate limit configuration
     * @return The same handler, for use in bind expressions
     */
    public fun <PATH : PathSpec> limit(
        handler: HttpHandler<PATH>,
        config: RateLimitConfig,
    ): HttpHandler<PATH> {
        configs[handler] = config
        return handler
    }

    /**
     * Registers a rate limit configuration for a handler and returns the handler unchanged.
     *
     * @param handler The handler to rate limit
     * @param requests Maximum number of requests allowed within the [window]
     * @param window The time window for the rate limit
     * @param keyStrategy How to identify the client for rate limiting
     * @param scope Optional shared scope name for grouping endpoints
     * @return The same handler, for use in bind expressions
     */
    public fun <PATH : PathSpec> limit(
        handler: HttpHandler<PATH>,
        requests: Int,
        window: Duration,
        keyStrategy: KeyStrategy = KeyStrategy.IP,
        scope: String? = null,
    ): HttpHandler<PATH> = limit(handler, RateLimitConfig(requests, window, keyStrategy, scope))

    context(runtime: ServerRuntime)
    override suspend fun intercept(
        request: HttpRequest<*>,
        cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse,
    ): HttpResponse {
        val rateLimitSettings = settings()
        if (!rateLimitSettings.enabled) {
            return cont(request)
        }

        val handler = request.path.match.value
        val config = configs[handler] ?: rateLimitSettings.defaultLimit ?: return cont(request)

        val key = generateKey(request, config)
        val now = runtime.clock.now()
        val result = checkRateLimit(cache(), key, config, now)

        val response = if (result.allowed) {
            cont(request)
        } else {
            HttpResponse.plainText(
                "Rate limit exceeded. Try again in ${result.retryAfter.inWholeSeconds} seconds.",
                status = HttpStatus.TooManyRequests,
            )
        }

        val prefix = rateLimitSettings.headerPrefix
        return response.copy(
            headers = response.headers.copy {
                add("${prefix}Limit", config.requests.toString())
                add("${prefix}Remaining", result.remaining.toString())
                add("${prefix}Reset", result.resetAt.epochSeconds.toString())
                if (!result.allowed) {
                    add(HttpHeader.RetryAfter, result.retryAfter.inWholeSeconds.toString())
                }
            }
        )
    }

    private fun generateKey(
        request: HttpRequest<*>,
        config: RateLimitConfig,
    ): String {
        val scope = config.scope ?: "${request.path.method}:${request.path.pathSegments}"

        val keyPart = when (config.keyStrategy) {
            KeyStrategy.GLOBAL -> "global"
            KeyStrategy.IP -> request.sourceIp
            KeyStrategy.USER -> {
                request.headers["Authorization"]?.root
                    ?: throw UnauthorizedException("Rate limiting by user requires authentication")
            }
            KeyStrategy.IP_AND_USER -> {
                val userId = request.headers["Authorization"]?.root ?: "anonymous"
                "${request.sourceIp}:$userId"
            }
        }

        return "ratelimit:$scope:$keyPart"
    }
}

internal data class RateLimitResult(
    val allowed: Boolean,
    val remaining: Int,
    val resetAt: Instant,
    val retryAfter: Duration,
)

/**
 * Checks and updates the rate limit using a fixed window algorithm backed by cache counters.
 *
 * Uses get-then-add pattern: reads the current count, increments it, and checks against the limit.
 * Note: this is not perfectly atomic under high concurrency, but is sufficient for rate limiting
 * where slight over-admission is acceptable.
 */
internal suspend fun checkRateLimit(
    cacheService: Cache,
    key: String,
    config: RateLimitConfig,
    now: Instant,
): RateLimitResult {
    val windowSeconds = config.window.inWholeSeconds
    val windowIndex = now.epochSeconds / windowSeconds
    val windowKey = "$key:$windowIndex"
    val windowStart = Instant.fromEpochSeconds(windowIndex * windowSeconds)
    val resetAt = windowStart + config.window

    val currentCount = cacheService.get<Int>(windowKey) ?: 0
    cacheService.add(windowKey, 1, config.window)
    val newCount = currentCount + 1

    val allowed = newCount <= config.requests
    val remaining = maxOf(0, config.requests - newCount)
    val retryAfter = if (allowed) Duration.ZERO else resetAt - now

    return RateLimitResult(
        allowed = allowed,
        remaining = remaining,
        resetAt = resetAt,
        retryAfter = retryAfter,
    )
}
