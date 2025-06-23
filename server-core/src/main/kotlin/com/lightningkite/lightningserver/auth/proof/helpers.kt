package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningdb.HasId
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.get
import com.lightningkite.lightningserver.cache.set
import com.lightningkite.lightningserver.cache.setIfNotExists
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.encodeUnwrappingString
import com.lightningkite.now
import kotlinx.datetime.Instant
import kotlin.math.exp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 *
 *  Tracks the number action attempts against a given [cacheKey].
 *  If more than [count] attempts occur within a [expires] time frame for the given [cacheKey],
 *  any subsequent requests for [blocked] amount of time will be denied.
 *  Subsequent requests in [blocked] time frame will reset the [blocked] time frame.
 *
 *  @param cacheKey The key you want to rate limit actions against.
 *  @param count The number of attempts that can be made in the [expires] time frame.
 *  @param expires The time frame for allowed attempts. Starts on the first attempt and resets every [expires] time passed.
 *  @param blocked The time frame the action will be blocked if too many attempts are made. Should always be greater than or equal to [expires].
 *  @param action The action you want to make against the [cacheKey]
 *
 *  @throws [BadRequestException] if attempt is denied.
 */

suspend inline fun <R> Cache.constrainAttemptRate(
    cacheKey: String,
    count: Int = 5,
    expires: Duration = 5.minutes,
    blocked: Duration = expires,
    action: () -> R,
): R {
    val startKey = "$cacheKey-start-time"

    val ct = (this.get<Int>(cacheKey) ?: 0)
    val start = this.setIfNotExists<Instant>(startKey, now(), expires)

    if (start && ct != 0 && ct <= count) {
        this.remove(cacheKey)
    }

    if (ct >= count) {
        val block = blocked.coerceAtLeast(expires)
        this.add(cacheKey, 1, block)
        throw BadRequestException("Too many attempts; please wait ${block.inWholeMinutes} minutes.")
    }

    return try {
        val result = action()
        remove(cacheKey)
        result
    } catch (e: Throwable) {
        this.add(cacheKey, 1, blocked.coerceAtLeast(expires))
        throw e
    }

}

fun <ID : Comparable<ID>> Authentication.SubjectHandler<*, ID>.idString(id: ID): String {
    return Serialization.json.encodeUnwrappingString(idSerializer, id)
}

suspend fun <T : HasId<ID>, ID : Comparable<ID>> Authentication.SubjectHandler<T, ID>.findUserIdString(
    property: String,
    value: String,
): String? {
    return findUser(property, value)?.let { idString(it._id) }
}