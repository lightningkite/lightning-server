package com.lightningkite.lightningserver.security

import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.RequestAuth
import com.lightningkite.lightningserver.auth.authAny
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.now
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class RateLimiter(
    val rateLimiterId: String = "general",
    val perAuth: suspend (auth: RequestAuth<*>?, ip: String) -> Float = { _, _ -> 1f },  // Ratio of real-time
    val maxRequestTime: suspend (auth: RequestAuth<*>?, ip: String) -> Duration = { _, _ -> 30.seconds },  // Ratio of real-time
    val leeway: Duration = 1.minutes,
    val cache: () -> Cache
) {
    init {
        Http.interceptors += { request, cont ->
            val a = request.authAny()
            gate(a, request.sourceIp) {
                cont(request)
            }
        }
    }

    suspend inline fun <T> gate(auth: RequestAuth<*>?, ip: String, action: () -> T): T {
        val now = now()
        val key = auth?.let { it.subject.name + "/" + it.rawId } ?: ip
        val cacheKey = "rateLimiter-$rateLimiterId-$key"
        val existing = cache().get<Long>(cacheKey)
        val rate = perAuth(auth, ip).toDouble()
        val cap = leeway * rate
        val borrowedTime = maxRequestTime(auth, ip)
        // User effectively "takes out a loan" to perform the request, and they can return the unused time later
        val min = (now + borrowedTime.div(rate) - cap).toEpochMilliseconds()
        if (existing == null || (now.toEpochMilliseconds() - existing) > cap.inWholeMilliseconds) {
            cache().set(cacheKey, min, timeToLive = 15.minutes)
        } else if (existing > now.toEpochMilliseconds()) throw HttpStatusException(
            LSError(
                http = HttpStatus.TooManyRequests.code,
                detail = "rate-limit-$rateLimiterId",
                message = "You're asking too much from the server; please wait before trying again.",
                data = Instant.fromEpochMilliseconds(existing).toString()
            )
        )
        else cache().add(cacheKey, (maxRequestTime(auth, ip).div(rate).inWholeMilliseconds.toInt()))

        var issue: Throwable? = null
        var result: T? = null
        try {
            result = action()
        } catch (t: Throwable) {
            issue = t
        }
        val done = now()
        val timeToReturn = (borrowedTime - (done - now)).div(rate)
        cache().add(cacheKey, (-timeToReturn.inWholeMilliseconds.toInt()))
        issue?.let { throw it }
        return result as T
    }
}