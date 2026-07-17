@file:OptIn(ExperimentalLightningServer::class)

package com.lightningkite.lightningserver.sessions.proofs.extensions

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.cache.*
import com.lightningkite.services.data.ExperimentalLightningServer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

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

/**
 * Atomically claims [cacheKey] for single use, returning `true` only for the very first caller.
 *
 * Backed by [setIfNotExists], which is atomic on every backend (Redis `SET NX`, DynamoDB conditional
 * put, synchronized [com.lightningkite.services.cache.MapCache]). Concurrent callers therefore race
 * safely: exactly one receives `true`. This is the building block for making one-time secrets
 * (signed proofs, TOTP codes, WebAuthN challenges) single-use within their validity window — a plain
 * `get`-then-`set` would have a time-of-check/time-of-use race that lets two concurrent replays both
 * succeed.
 *
 * @param cacheKey Identifies the thing being consumed (must be derived from the secret, not the user).
 * @param ttl How long the claim is remembered; set to at least the remaining validity of the secret.
 * @return `true` if this caller claimed it (proceed), `false` if it was already consumed (reject).
 */
context(server: ServerRuntime)
public suspend fun Cache.claimOnce(cacheKey: String, ttl: Duration): Boolean =
    setIfNotExists(cacheKey, now(), ttl)

context(server: ServerRuntime)
public suspend inline fun <R> Cache.constrainAttemptRate(
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