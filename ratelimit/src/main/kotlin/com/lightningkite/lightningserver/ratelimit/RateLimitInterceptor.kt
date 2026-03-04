package com.lightningkite.lightningserver.ratelimit

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawWebsocketPath
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketHandlerInterceptor
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.cache.get
import kotlinx.serialization.builtins.serializer
import java.util.IdentityHashMap
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Interceptor that enforces rate limiting for both HTTP and WebSocket requests.
 *
 * Supports two algorithms:
 * - **Fixed window**: Allows a fixed number of requests per time window.
 * - **Token bucket**: Each request borrows time; requests are rejected when accumulated debt exceeds leeway.
 *
 * Rate limits can be configured per-endpoint using [limit] or globally via [RateLimitSettings.defaultLimit].
 * Rate limit counters are stored in the [Cache] service abstraction, enabling distributed rate limiting.
 *
 * ## Usage
 * ```kotlin
 * object Server : ServerBuilder() {
 *     val cache = setting("cache", Cache.Settings())
 *     val rateLimiter = install(RateLimitInterceptor(
 *         cache = cache,
 *         settings = Runtime.Constant(RateLimitSettings(
 *             defaultLimit = RateLimitConfig(borrowTime = 1.seconds, leeway = 30.seconds),
 *         )),
 *         rateMultiplier = { request ->
 *             // Give different users different rate limits
 *             val auth = request[Authentication.CacheKey]
 *             if (auth != null) 2.0f else 0.5f
 *         },
 *         ignore = { request ->
 *             // Skip rate limiting for static files
 *             (request as? HttpRequest)?.path?.pathSegments?.firstOrNull() == "static"
 *         },
 *     ))
 *
 *     val endpoint = path.path("api").post bind rateLimiter.limit(
 *         HttpHandler { HttpResponse.plainText("OK") },
 *         requests = 10,
 *         window = 1.minutes,
 *     )
 * }
 * ```
 *
 * @param cache Runtime providing the cache service for storing rate limit counters
 * @param settings Runtime providing the rate limit configuration
 * @param rateMultiplier Optional callback returning a multiplier for the effective rate limit.
 *   Higher values allow more requests. Defaults to 1.0 when null.
 * @param ignore Optional predicate; when it returns true, rate limiting is skipped for that request.
 */
public class RateLimitInterceptor(
    private val cache: Runtime<Cache>,
    private val settings: Runtime<RateLimitSettings>,
    private val rateMultiplier: (suspend context(ServerRuntime) (Request<*>) -> Float)? = null,
    private val ignore: ((Request<*>) -> Boolean)? = null,
) : HttpInterceptor, WebSocketHandlerInterceptor {

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
        if (!rateLimitSettings.enabled) return cont(request)
        if (ignore?.invoke(request) == true) return cont(request)

        val handler = request.path.match.value
        val config = configs[handler] ?: rateLimitSettings.defaultLimit ?: return cont(request)
        val multiplier = rateMultiplier?.let { it(runtime, request) } ?: 1.0f

        val key = generateKey(request.sourceIp, request.headers, request.path.pathSegments.toString(), config)
        val now = runtime.clock.now()

        val borrowTime = config.borrowTime
        val result = if (borrowTime != null) {
            checkTokenBucket(cache(), key, borrowTime, config.leeway, multiplier, now)
        } else {
            checkRateLimit(cache(), key, config, now, multiplier)
        }

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
                add("${prefix}Limit", result.limit.toString())
                add("${prefix}Remaining", result.remaining.toString())
                add("${prefix}Reset", result.resetAt.epochSeconds.toString())
                if (!result.allowed) {
                    add(HttpHeader.RetryAfter, result.retryAfter.inWholeSeconds.toString())
                }
            }
        )
    }

    override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> {
        return object : WebSocketHandler<PATH, T> by handler {
            context(serverRuntime: ServerRuntime)
            override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                val rateLimitSettings = settings()
                if (!rateLimitSettings.enabled) return handler.willConnect(request)
                if (ignore?.invoke(request) == true) return handler.willConnect(request)

                val config = rateLimitSettings.defaultLimit ?: return handler.willConnect(request)
                val multiplier = rateMultiplier?.let { it(serverRuntime, request) } ?: 1.0f
                val key = generateKey(request.sourceIp, request.headers, request.path.pathSegments.toString(), config)
                val now = serverRuntime.clock.now()

                val borrowTime = config.borrowTime
                val result = if (borrowTime != null) {
                    checkTokenBucket(cache(), key, borrowTime, config.leeway, multiplier, now)
                } else {
                    checkRateLimit(cache(), key, config, now, multiplier)
                }

                if (!result.allowed) {
                    throw HttpStatusException(
                        status = HttpStatus.TooManyRequests,
                        message = "Rate limit exceeded. Try again in ${result.retryAfter.inWholeSeconds} seconds.",
                    )
                }

                return handler.willConnect(request)
            }
        }
    }

    private fun generateKey(
        sourceIp: String,
        headers: HttpHeaders,
        pathIdentifier: String,
        config: RateLimitConfig,
    ): String {
        val scope = config.scope ?: pathIdentifier

        val keyPart = when (config.keyStrategy) {
            KeyStrategy.GLOBAL -> "global"
            KeyStrategy.IP -> sourceIp
            KeyStrategy.USER -> {
                headers["Authorization"]?.root
                    ?: throw UnauthorizedException("Rate limiting by user requires authentication")
            }
            KeyStrategy.IP_AND_USER -> {
                val userId = headers["Authorization"]?.root ?: "anonymous"
                "$sourceIp:$userId"
            }
        }

        return "ratelimit:$scope:$keyPart"
    }
}

internal data class RateLimitResult(
    val allowed: Boolean,
    val limit: Int,
    val remaining: Int,
    val resetAt: Instant,
    val retryAfter: Duration,
)

/**
 * Checks and updates the rate limit using a fixed window algorithm backed by cache counters.
 */
internal suspend fun checkRateLimit(
    cacheService: Cache,
    key: String,
    config: RateLimitConfig,
    now: Instant,
    multiplier: Float = 1.0f,
): RateLimitResult {
    val effectiveRequests = (config.requests * multiplier).toInt().coerceAtLeast(1)
    val windowSeconds = config.window.inWholeSeconds
    val windowIndex = now.epochSeconds / windowSeconds
    val windowKey = "$key:$windowIndex"
    val windowStart = Instant.fromEpochSeconds(windowIndex * windowSeconds)
    val resetAt = windowStart + config.window

    val currentCount = cacheService.get<Int>(windowKey) ?: 0
    cacheService.add(windowKey, 1, config.window)
    val newCount = currentCount + 1

    val allowed = newCount <= effectiveRequests
    val remaining = maxOf(0, effectiveRequests - newCount)
    val retryAfter = if (allowed) Duration.ZERO else resetAt - now

    return RateLimitResult(
        allowed = allowed,
        limit = effectiveRequests,
        remaining = remaining,
        resetAt = resetAt,
        retryAfter = retryAfter,
    )
}

/**
 * Checks and updates the rate limit using a token bucket algorithm.
 *
 * Each request "borrows" [borrowTime] / [multiplier] of time. The cache stores the epoch millis
 * when all accumulated debt is repaid. If current debt exceeds [leeway], the request is rejected.
 */
internal suspend fun checkTokenBucket(
    cacheService: Cache,
    key: String,
    borrowTime: Duration,
    leeway: Duration,
    multiplier: Float,
    now: Instant,
): RateLimitResult {
    val effectiveBorrowMs = (borrowTime.inWholeMilliseconds / multiplier).toLong().coerceAtLeast(1)
    val leewayMs = leeway.inWholeMilliseconds
    val debtKey = "$key:debt"

    val nowMs = now.toEpochMilliseconds()
    val storedDeadline = cacheService.get<Long>(debtKey) ?: nowMs
    val currentDebtMs = maxOf(0L, storedDeadline - nowMs)

    val allowed = currentDebtMs <= leewayMs
    if (allowed) {
        val newDeadline = maxOf(nowMs, storedDeadline) + effectiveBorrowMs
        val ttlMs = newDeadline - nowMs + leewayMs + effectiveBorrowMs
        cacheService.set(debtKey, newDeadline, Long.serializer(), Duration.ofMilliseconds(ttlMs))
    }

    val remaining = if (allowed) maxOf(0, ((leewayMs - currentDebtMs) / effectiveBorrowMs).toInt()) else 0
    val retryAfter = if (allowed) Duration.ZERO else Duration.ofMilliseconds(currentDebtMs - leewayMs)
    val resetAt = if (allowed) {
        Instant.fromEpochMilliseconds(maxOf(nowMs, storedDeadline) + effectiveBorrowMs)
    } else {
        Instant.fromEpochMilliseconds(storedDeadline)
    }

    return RateLimitResult(
        allowed = allowed,
        limit = ((leewayMs / effectiveBorrowMs) + 1).toInt(),
        remaining = remaining,
        resetAt = resetAt,
        retryAfter = retryAfter,
    )
}

private fun Duration.Companion.ofMilliseconds(ms: Long): Duration =
    (ms / 1000).seconds + (ms % 1000).milliseconds

private val Long.seconds: Duration get() = Duration.parse("${this}s")
private val Long.milliseconds: Duration get() = Duration.parse("PT0.${this.toString().padStart(3, '0')}S")
