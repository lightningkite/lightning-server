package com.lightningkite.lightningserver.ratelimit

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.cache.*
import com.lightningkite.services.data.FloatRange
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.contracts.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant


/**
 * Settings controlling the behavior of a [RateLimitInterceptor].
 *
 * @property headerPrefix Prefix used for the rate-limit response headers (e.g. `X-RateLimit-Identity`).
 * @property rateLimiterId Identifier used to namespace cache keys so multiple rate limiters do not collide.
 * @property includeHeaders When `true`, rate-limit diagnostic headers are added to each response.
 */
@Serializable
public data class RateLimitSettings(
    val headerPrefix: String = "X-RateLimit-",
    val rateLimiterId: String = "general",
    val includeHeaders: Boolean = true,
)


/**
 * Per-request limits describing how much server time a given caller is permitted to consume.
 *
 * The rate limiter uses a borrow-and-repay model: each request reserves [borrowTime] / [multiplier]
 * of future time up front, then refunds the unused portion after the request completes. When a
 * caller's reserved time would extend further than [leeway] into the future, subsequent requests
 * are rejected with HTTP 429 until the reservation catches up.
 *
 * @property key Unique identifier for the caller (e.g. IP address, user id). Requests sharing a key
 *   share the same budget.
 * @property multiplier Divisor applied to [borrowTime] and [overhead]. A value between 0.0 - 1.0. Higher values loosen
 *   the limit (cheaper requests), lower values tighten it (more expensive requests).
 * @property leeway How far ahead of real time the caller is allowed to reserve before being throttled.
 *   Larger values permit bigger bursts.
 * @property borrowTime Nominal amount of time reserved up front for a single request, before being
 *   divided by [multiplier].
 * @property overhead Extra time charged against the caller after each request to account for work
 *   not captured by the measured duration.
 */
public data class RequestLimits(
    val key: String,
    @FloatRange(0.0, 1.0) val multiplier: Double = 0.1,
    val leeway: Duration = 200.seconds,
    val borrowTime: Duration = 10.seconds,
    val overhead: Duration = 0.25.seconds,
)


/**
 * HTTP and WebSocket interceptor that throttles requests based on caller-specific [RequestLimits].
 *
 * The interceptor tracks per-caller state in [cache], reserves budget before invoking the wrapped
 * handler, and refunds unused budget after it completes. Callers that exceed their budget receive
 * an HTTP 429 response (or, for WebSocket upgrades, are rejected before [WebSocketHandler.willConnect]
 * is invoked). When [settings] resolves to `null`, or [requestLimits] returns `null` for a given
 * request, the interceptor passes the request through unmodified.
 *
 * @property settings Runtime-resolved [RateLimitSettings]; if `null`, rate limiting is disabled.
 * @property cache Runtime-resolved [Cache] used to persist per-key reservation state.
 * @property requestLimits Function that computes the [RequestLimits] for a request, or returns
 *   `null` to exempt the request from rate limiting.
 */
public class RateLimitInterceptor(
    private val settings: Runtime<RateLimitSettings?>,
    private val cache: Runtime<Cache>,
    private val requestLimits: suspend context(ServerRuntime) (Request<*>) -> RequestLimits?,
) : HttpInterceptor, WebSocketHandlerInterceptor {

    private val logger = KotlinLogging.logger("com.lightningkite.lightningserver.ratelimit")

    override val name: String = "RateLimit"

    context(runtime: ServerRuntime)
    override suspend fun intercept(
        request: HttpRequest<*>,
        cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse,
    ): HttpResponse {
        val settings = settings() ?: return cont(request)

        val result: HttpResponse
        val info = gate(
            settings,
            requestLimits(request) ?: return cont(request)
        ) {
            result = cont(request)
        }

        return if (settings.includeHeaders && info != null) result.copy(
            headers = result.headers + HttpHeaders(
                "${settings.headerPrefix}Identity" to info.key,
                "${settings.headerPrefix}RemainingTime" to info.remainingTime.toString(),
                "${settings.headerPrefix}AvailableAfter" to info.availableAfter.toString(),
            )
        ) else result
    }

    override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> {
        return object : WebSocketHandler<PATH, T> by handler {
            context(serverRuntime: ServerRuntime)
            override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                val settings = settings() ?: return handler.willConnect(request)

                val result: T
                gate(settings, requestLimits(request) ?: return handler.willConnect(request)) {
                    result = handler.willConnect(request)
                }
                return result
            }
        }
    }

    private data class RateLimitInfo(
        val key: String,
        val remainingTime: Duration,
        @Contextual val availableAfter: Instant,
    )

    @Suppress("WRONG_INVOCATION_KIND")
    @OptIn(ExperimentalContracts::class)
    context(runtime: ServerRuntime)
    private suspend inline fun gate(
        settings: RateLimitSettings,
        limits: RequestLimits,
        action: () -> Unit,
    ): RateLimitInfo? {
        contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
        val now = now()
        val cacheKey = "rateLimiter-${settings.rateLimiterId}-${limits.key}"
        val stoppedUntil = cache().get<Long>(cacheKey)
        val stoppedUntilTime = stoppedUntil?.let { Instant.fromEpochMilliseconds(it) }
        val borrowedValue = limits.borrowTime / limits.multiplier

//        logger.trace { "stoppedUntilTime: ${stoppedUntilTime?.let { "$it (rel ${it - now()})" }}" }
//        logger.trace { "borrowedValue: $borrowedValue" }

        if (stoppedUntilTime == null) {
            logger.trace {
                "stoppedUntilTime virtual before borrow: ${
                    now.minus(limits.leeway).let { "$it (rel ${it - now()})" }
                }"
            }
            cache().set(
                cacheKey,
                now.minus(limits.leeway).plus(borrowedValue).toEpochMilliseconds(),
                timeToLive = 15.minutes
            )
        } else if (now < stoppedUntilTime) {
            val currentWait = stoppedUntilTime - now
            val newWait = (currentWait.inWholeSeconds + 1).seconds
            val newUntilTime = now + newWait
            cache().set(cacheKey, newUntilTime.toEpochMilliseconds())
            throw HttpStatusException(
                LSError(
                    http = HttpStatus.TooManyRequests.code,
                    detail = "rate-limit-${settings.rateLimiterId}",
                    message = "You're asking too much from the server; please wait before trying again.",
                    data = runtime.internalSerialization.json.encodeToString(buildJsonObject {
                        put("at", newUntilTime.toString())
                        put("wait", (newWait).toString())
                    })
                )
            )
        } else if (now - stoppedUntilTime > limits.leeway) {
            logger.trace {
                "stoppedUntilTime virtual before borrow: ${
                    now.minus(limits.leeway).let { "$it (rel ${it - now()})" }
                }"
            }
            cache().set(
                cacheKey,
                now.minus(limits.leeway).plus(borrowedValue).toEpochMilliseconds(),
                timeToLive = 15.minutes
            )
        } else
            cache().add(cacheKey, borrowedValue.inWholeMilliseconds.toInt())

        @Suppress("Deprecation")
        logger.trace(
            "stoppedUntilTime after borrow: ${
                cache().get<Long>(cacheKey)?.let { Instant.fromEpochMilliseconds(it) }
                    ?.let { "$it (rel ${it - now()})" }
            }"
        )

        var issue: Throwable? = null
        try {
            action()
        } catch (t: Throwable) {
            issue = t
        }
        val done = now()
        val takenValue = (done - now + limits.overhead) / limits.multiplier
        val valueToReturn = borrowedValue - takenValue
//        logger.trace { "time taken: ${done - now}" }
//        logger.trace { "takenValue: $takenValue" }
//        logger.trace { "valueToReturn: $valueToReturn" }
        cache().add(cacheKey, (-valueToReturn.inWholeMilliseconds.toInt()))

        @Suppress("Deprecation")
        logger.trace(
            "stoppedUntilTime after return: ${
                cache().get<Long>(cacheKey)?.let { Instant.fromEpochMilliseconds(it) }
                    ?.let { "$it (rel ${it - now()})" }
            }"
        )
        issue?.let { throw it }
        val final = cache().get<Long>(cacheKey)?.let { Instant.fromEpochMilliseconds(it) }
        return if (settings()?.includeHeaders == true) RateLimitInfo(
            key = limits.key,
            remainingTime = final?.let { now() - it } ?: limits.leeway,
            availableAfter = final ?: now().minus(limits.leeway),
        ) else null
    }

}
