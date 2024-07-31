package com.lightningkite.lightningserver.security

import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.authAny
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.websocket.WebSockets
import com.lightningkite.lightningserver.websocket.WsInterceptor
import com.lightningkite.now
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RateLimiter(
    val rateLimiterId: String = "general",
    val perAuth: suspend (auth: RequestAuth<*>?, ip: String) -> Float = { _, _ -> 0.1f },  // Ratio of real-time
    val borrowTime: suspend (auth: RequestAuth<*>?, ip: String) -> Duration = { _, _ -> 10.seconds },
    val ignore: (Request) -> Boolean = { false },
    val virtualDurationModifier: (Request, Duration) -> Duration = { _, it -> it + 0.25.seconds }, // to reflect balancer overhead
    val leeway: Duration = 200.seconds,
    val cache: () -> Cache,
    val includeHeaders: Boolean = true,
    val log: ((String)->Unit)? = null
) {
    val httpInterceptor: HttpInterceptor = { request, cont ->
        val result: HttpResponse
        val info = gate(request, request.authAny(), request.sourceIp) {
            result = cont(request)
        }
        if(includeHeaders && info != null) result.copy(headers = result.headers + HttpHeaders(
            "X-RateLimit-Identity" to info.id,
            "X-RateLimit-RemainingTime" to info.remainingTime.toString(),
            "X-RateLimit-AvailableAfter" to info.availableAfter.toString(),
        )) else result
    }
    val wsInterceptor: WsInterceptor = { request, cont ->
        gate(request, request.authAny(), request.sourceIp) {
            cont(request)
        }
    }

    data class RateLimitInfo(
        val remainingTime: Duration,
        val id: String,
        val availableAfter: Instant
    )

    @OptIn(ExperimentalContracts::class)
    suspend inline fun gate(request: Request, auth: RequestAuth<*>?, ip: String, action: () -> Unit): RateLimitInfo? {
        contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
        if (ignore(request)) return null
        val now = now()
        val key = auth?.let { it.subject.name + "/" + it.rawId } ?: ip
        val cacheKey = "rateLimiter-$rateLimiterId-$key"
        val stoppedUntil = cache().get<Long>(cacheKey)
        val stoppedUntilTime = stoppedUntil?.let { Instant.fromEpochMilliseconds(it) }
        val rate = perAuth(auth, ip).toDouble()
        val borrowTime = borrowTime(auth, ip)
        val borrowedValue = borrowTime / rate

        log?.invoke("stoppedUntilTime: ${stoppedUntilTime?.let { "$it (rel ${it - now()})" }}")
        log?.invoke("borrowedValue: $borrowedValue")

        if (stoppedUntilTime == null) {
            log?.invoke("stoppedUntilTime virtual before borrow: ${now.minus(leeway).let { "$it (rel ${it - now()})" }}")
            cache().set(cacheKey, now.minus(leeway).plus(borrowedValue).toEpochMilliseconds(), timeToLive = 15.minutes)
        } else if (now < stoppedUntilTime)
            throw HttpStatusException(
                LSError(
                    http = HttpStatus.TooManyRequests.code,
                    detail = "rate-limit-$rateLimiterId",
                    message = "You're asking too much from the server; please wait before trying again.",
                    data = Serialization.json.encodeToString(buildJsonObject {
                        put("at", stoppedUntilTime.toString())
                        put("wait", (stoppedUntilTime - now).toString())
                    })
                )
            )
        else if (now - stoppedUntilTime > leeway) {
            log?.invoke("stoppedUntilTime virtual before borrow: ${now.minus(leeway).let { "$it (rel ${it - now()})" }}")
            cache().set(cacheKey, now.minus(leeway).plus(borrowedValue).toEpochMilliseconds(), timeToLive = 15.minutes)
        } else
            cache().add(cacheKey, borrowedValue.inWholeMilliseconds.toInt())

        log?.invoke("stoppedUntilTime after borrow: ${cache().get<Long>(cacheKey)?.let { Instant.fromEpochMilliseconds(it) }?.let { "$it (rel ${it - now()})" }}")

        var issue: Throwable? = null
        try {
            action()
        } catch (t: Throwable) {
            issue = t
        }
        val done = now()
        val takenValue = virtualDurationModifier(request, done - now) / rate
        val valueToReturn = borrowedValue - takenValue
        log?.invoke("time taken: ${done - now}")
        log?.invoke("takenValue: $takenValue")
        log?.invoke("valueToReturn: $valueToReturn")
        cache().add(cacheKey, (-valueToReturn.inWholeMilliseconds.toInt()))
        log?.invoke("stoppedUntilTime after return: ${cache().get<Long>(cacheKey)?.let { Instant.fromEpochMilliseconds(it) }?.let { "$it (rel ${it - now()})" }}")
        issue?.let { throw it }
        val final = cache().get<Long>(cacheKey)?.let { Instant.fromEpochMilliseconds(it) }
        return if(includeHeaders) RateLimitInfo(
            id = key,
            remainingTime = final?.let { now() - it } ?: leeway,
            availableAfter = final ?: now().minus(leeway),
        ) else null
    }

//    suspend inline fun <T> gate(request: Request, auth: RequestAuth<*>?, ip: String, action: () -> T): T {
//        if (ignore(request)) return action()
//        val now = now()
//        val key = auth?.let { it.subject.name + "/" + it.rawId } ?: ip
//        val cacheKey = "rateLimiter-$rateLimiterId-$key"
//        val existing = cache().get<Long>(cacheKey)
//        val rate = perAuth(auth, ip).toDouble()
//        val cap = leeway * rate
//        val borrowedTime = borrowTime(auth, ip)
//        // User effectively "takes out a loan" to perform the request, and they can return the unused time later
//        val min = (now + borrowedTime.div(rate) - cap).toEpochMilliseconds()
//        if (existing == null || (now.toEpochMilliseconds() - existing) > cap.inWholeMilliseconds) {
//            cache().set(cacheKey, min, timeToLive = 15.minutes)
//        } else if (existing > now.toEpochMilliseconds()) throw HttpStatusException(
//            LSError(
//                http = HttpStatus.TooManyRequests.code,
//                detail = "rate-limit-$rateLimiterId",
//                message = "You're asking too much from the server; please wait before trying again.",
//                data = Serialization.json.encodeToString(buildJsonObject {
//                    put("at", Instant.fromEpochMilliseconds(existing).toString())
//                    put("wait", (Instant.fromEpochMilliseconds(existing) - now).toString())
//                })
//            )
//        )
//        else cache().add(cacheKey, (borrowTime(auth, ip).div(rate).inWholeMilliseconds.toInt()))
//
//        var issue: Throwable? = null
//        var result: T? = null
//        try {
//            result = action()
//        } catch (t: Throwable) {
//            issue = t
//        }
//        val done = now()
//        val timeToReturn = (borrowedTime - costModifier(request, done - now)).div(rate)
//        cache().add(cacheKey, (-timeToReturn.inWholeMilliseconds.toInt()))
//        issue?.let { throw it }
//        return result as T
//    }
}