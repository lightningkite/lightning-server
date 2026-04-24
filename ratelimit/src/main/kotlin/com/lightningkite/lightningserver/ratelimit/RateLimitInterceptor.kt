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
 * The rate limiter uses a borrow-and-repay model: each request reserves [borrowTime] of future time up front,
 * then refunds the unused portion after the request completes. When a
 * caller's reserved time would extend further than [leeway] into the future, subsequent requests
 * are rejected with HTTP 429 until the reservation catches up.
 *
 * @property key Unique identifier for the caller (e.g. IP address, user id). Requests sharing a key
 *   share the same budget.
 * @property multiplier A Value greater than 0.0. [multiplier] is applied to the final time used by a request. Values
 *   higher than 1 tightens the limit (more expensive requests), values less than 1 loosen it (cheaper requests).
 *   Negative or zero values result in undefined behavior.
 * @property leeway How far ahead of real time the caller is allowed to reserve before being throttled.
 *   Larger values permit bigger bursts.
 * @property borrowTime Nominal amount of time reserved up front for a single request.
 * @property overhead Extra time charged against the caller after each request to account for work
 *   not captured by the measured duration.
 */
public data class RequestLimits(
    val key: String,
    val multiplier: Double = 10.0,
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

    // this suppression is okay, it's mad because we catch throwables when executing the lambda and then rethrow later.
    @Suppress("WRONG_INVOCATION_KIND")
    @OptIn(ExperimentalContracts::class)
    context(runtime: ServerRuntime)
    private suspend inline fun gate(
        settings: RateLimitSettings,
        limits: RequestLimits,
        action: () -> Unit,
    ): RateLimitInfo? {
        contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
        val start = now()
        val cacheKey = "rateLimiter-${settings.rateLimiterId}-${limits.key}:"
        val stoppedUntil = cache().get<Long>(cacheKey)
        val stoppedUntilTime = stoppedUntil?.let { Instant.fromEpochMilliseconds(it) }

        logger.trace {
            """${limits.key}: stoppedUntilTime: ${stoppedUntilTime?.let { "$it (rel ${it - now()})" }}
               ${limits.key}: borrowedValue: ${limits.borrowTime}""".trimMargin()
        }

        val stoppedUntilNew: Long = if (stoppedUntilTime == null || start - stoppedUntilTime > limits.leeway) {
            logger.trace {
                "${limits.key}: stoppedUntilTime virtual before borrow: ${
                    start.minus(limits.leeway).let { "$it (rel ${it - now()})" }
                }"
            }

            val stoppedUntil = start.minus(limits.leeway).plus(limits.borrowTime).toEpochMilliseconds()
            cache().set<Long>(cacheKey, stoppedUntil, timeToLive = 15.minutes)

            stoppedUntil
        } else if (start < stoppedUntilTime) {
            val currentWait = stoppedUntilTime - start
            val newWait = (currentWait.inWholeSeconds + 1).seconds
            val newUntilTime = start + newWait
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
        } else
            cache().add(cacheKey, limits.borrowTime.inWholeMilliseconds)

        logger.trace {
            val time = Instant.fromEpochMilliseconds(stoppedUntilNew)
            "${limits.key}: stoppedUntilTime after borrow: $time (rel ${time - now()})"
        }

        var issue: Throwable? = null
        try {
            action()
        } catch (t: Throwable) {
            issue = t
        }
        val done = now()
        val takenValue = (done - start + limits.overhead) * limits.multiplier
        val valueToReturn = limits.borrowTime - takenValue

        val afterRefund = cache().add(cacheKey, -valueToReturn.inWholeMilliseconds)
        val final = Instant.fromEpochMilliseconds(afterRefund)

        logger.trace {
            """${limits.key}: time taken: ${done - start}
               ${limits.key}: takenValue: $takenValue
               ${limits.key}: valueToReturn: $valueToReturn
               ${limits.key}: stoppedUntilTime after return: $final (rel ${final - now()})""".trimMargin()
        }

        issue?.let { throw it }

        return if (settings()?.includeHeaders == true) {
            RateLimitInfo(
                key = limits.key,
                remainingTime = now() - final,
                availableAfter = final,
            )
        } else null
    }

}
