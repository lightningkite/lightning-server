package com.lightningkite.lightningserver.ratelimit

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.ExecutionInterceptor
import com.lightningkite.lightningserver.runtime.Invocation
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.websockets.MultiplexWebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.services.cache.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.math.ceil


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
 * @property borrowTime Nominal amount of time reserved up front for a single request. A rejected request is
 *   charged this much, so a caller retrying more often than this while blocked stays blocked.
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
 * Throttles each caller to the server time their [RequestLimits] allow.
 *
 * Every request a client makes is checked and charged on its own: a request the engine received, each
 * `/meta/bulk` part, and each socket connect, multiplexed virtual sockets included. Internal endpoint
 * calls are not, since the request that made them already pays for their time. A caller over budget gets
 * an HTTP 429 with a `Retry-After`, or a rejected socket. When [settings] resolves to `null`, or
 * [requestLimits] returns `null` for a request, the request passes through unchecked.
 *
 * The rate-limit headers go on the response the client receives. For a `/meta/bulk` call that is the bulk
 * response, which reports the furthest any of its parts reserved or was told to wait.
 *
 * Install it after the `CorsInterceptor`, so a browser can read its 429s and the rate-limit headers it
 * adds to each response. Pass handlers that only carry other requests, such as `/meta/bulk` and a
 * [MultiplexWebSocketHandler], in [exempt] and [exemptSockets], since what they carry is charged itself:
 *
 * ```kotlin
 * install(CorsInterceptor(cors))
 * install(RateLimitInterceptor(rateLimit, cache, exempt = listOf(meta.bulk), exemptSockets = listOf(multiplex)) { request ->
 *     RequestLimits(key = "ip:${request.sourceIp}")
 * })
 * ```
 *
 * @param exempt Handlers whose requests are neither checked nor charged.
 * @param exemptSockets Socket handlers whose connections are neither checked nor charged.
 */
public class RateLimitInterceptor(
    private val settings: Runtime<RateLimitSettings?>,
    private val cache: Runtime<Cache>,
    private val exempt: List<HttpHandler<*>> = emptyList(),
    private val exemptSockets: List<WebSocketHandler<*, *>> = emptyList(),
    private val requestLimits: suspend context(ServerRuntime) (Request<*>) -> RequestLimits?,
) : ExecutionInterceptor, HttpInterceptor {

    private val logger = KotlinLogging.logger("com.lightningkite.lightningserver.ratelimit")

    override val name: String = "RateLimit"

    /**
     * The furthest the caller has reserved or been told to wait, handed from the execution half to the HTTP
     * half, which adds it as headers.
     *
     * The requests inside one, such as `/meta/bulk` parts, share it, so the response the client actually
     * receives reports all of them.
     */
    private class Reservation(key: CoroutineContext.Key<Reservation>) : AbstractCoroutineContextElement(key) {
        var identity: String? = null
            private set
        var until: Instant? = null
            private set

        // Claude: bulk parts run concurrently.
        @Synchronized
        fun record(identity: String, until: Instant) {
            if (this.until?.let { it >= until } == true) return
            this.identity = identity
            this.until = until
        }
    }

    // Claude: one key per limiter, or a second installed limiter would read this one's reservation and repeat its headers.
    private val reservationKey = object : CoroutineContext.Key<Reservation> {}

    private fun headers(settings: RateLimitSettings, identity: String, until: Instant, now: Instant): HttpHeaders =
        if (!settings.includeHeaders) HttpHeaders.EMPTY
        else HttpHeaders(
            "${settings.headerPrefix}Identity" to identity,
            "${settings.headerPrefix}RemainingTime" to (now - until).toString(),
            "${settings.headerPrefix}AvailableAfter" to until.toString(),
        )

    /**
     * Checks and charges the caller's budget, kept in the cache as the instant they have reserved up to.
     *
     * A request borrows [RequestLimits.borrowTime] up front and is refunded what it didn't use. A caller
     * whose reservation is already past now is rejected, and the rejection keeps what it borrowed, so a
     * caller retrying more often than every [RequestLimits.borrowTime] stays blocked. A reservation never
     * lags more than [RequestLimits.leeway] behind now, which is how far an idle caller can burst.
     */
    context(runtime: ServerRuntime)
    override suspend fun <T> intercept(invocation: Invocation, cont: suspend context(ServerRuntime) () -> T): T {
        val settings = settings() ?: return cont()

        val request: Request<*> = when (invocation) {
            // requests initiated by the server itself (such as calling an api endpoint within another api endpoint) are ignored, they are measured as part of the original request's time.
            is Invocation.Http ->
                if (invocation.origin == Invocation.Http.Origin.Server || invocation.request.path.tryResolve()?.value in exempt) return cont()
                else invocation.request

            // Claude: only connecting admits a socket; its later phases belong to a connection already paid for.
            is Invocation.WebSocket ->
                if (invocation.execution.phase != Execution.WebSocket.Phase.Connect || invocation.request.path.tryResolve()?.value in exemptSockets) return cont()
                else invocation.request

            is Invocation.Unrequested -> return cont()
        }
        val limits = requestLimits(request) ?: return cont()

        val key = CacheKey.OfInstant("rateLimiter-${settings.rateLimiterId}-${limits.key}:")
        val reservation = currentCoroutineContext()[reservationKey] ?: Reservation(reservationKey)

        val start = now()
        val floor = start - limits.leeway
        val reserved = cache().get(key)?.takeIf { it > floor }

        // Claude: a reservation stops mattering once it is leeway behind now, so the key lives exactly that long.
        // Caches reject a TTL under 1 ms, which a free request on an idle caller would otherwise produce.
        fun ttl(until: Instant) = (until - start + limits.leeway).coerceAtLeast(1.milliseconds)

        if (reserved != null && reserved > start) {
            // Claude: a rejected request still costs borrowTime, so a caller retrying faster than that stays blocked.
            val until = cache().add(key, limits.borrowTime, timeToLive = ttl(reserved + limits.borrowTime))
            val wait = until - start
            reservation.record(limits.key, until)
            throw HttpStatusException(
                status = HttpStatus.TooManyRequests,
                detail = "rate-limit-${settings.rateLimiterId}",
                message = "You're asking too much from the server; please wait before trying again.",
                data = runtime.externalSerialization.json.encodeToString(buildJsonObject {
                    put("at", until.toString())
                    put("wait", wait.toString())
                }),
                headers = HttpHeaders(HttpHeader.RetryAfter to ceil(wait / 1.seconds).toLong().toString()) +
                    headers(settings, limits.key, until, start),
            )
        }

        val until = if (reserved == null) {
            val reservedUntil = floor + limits.borrowTime
            cache().set(key, reservedUntil, timeToLive = ttl(reservedUntil))
            reservedUntil
        } else {
            cache().add(key, limits.borrowTime, timeToLive = ttl(reserved + limits.borrowTime))
        }

        reservation.record(limits.key, until)

        try {
            return withContext(reservation) { cont() }
        } finally {
            val charge = (now() - start + limits.overhead) * limits.multiplier
            // Claude: a cancelled request, such as one whose client disconnected, still has to be settled.
            withContext(NonCancellable) {
                cache().add(key, charge - limits.borrowTime, timeToLive = ttl(until + charge - limits.borrowTime))
            }
            logger.trace { "${limits.key}: charged $charge for a request that took ${now() - start}" }
        }
    }

    context(runtime: ServerRuntime)
    override suspend fun <PATH : PathSpec> intercept(
        request: HttpRequest<PATH>,
        cont: suspend context(ServerRuntime) (HttpRequest<PATH>) -> HttpResponse,
    ): HttpResponse {
        val settings = settings() ?: return cont(request)
        // Claude: an exempt request, like /meta/bulk, has no reservation yet; this one collects its parts'.
        val reservation = currentCoroutineContext()[reservationKey] ?: Reservation(reservationKey)
        val response = withContext(reservation) { cont(request) }
        val identity = reservation.identity ?: return response
        val until = reservation.until ?: return response
        return response.copy(headers = response.headers + headers(settings, identity, until, now()))
    }
}
