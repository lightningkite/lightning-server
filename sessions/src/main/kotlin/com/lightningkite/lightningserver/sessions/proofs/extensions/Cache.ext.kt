@file:OptIn(ExperimentalLightningServer::class)

package com.lightningkite.lightningserver.sessions.proofs.extensions

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.services.cache.*
import com.lightningkite.services.data.ExperimentalLightningServer
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

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

/**
 *  Tracks the number of action attempts against a given [cacheKey] and applies rate limiting with
 *  **exponential backoff** so that repeated abuse of the same key is punished progressively harder.
 *
 *  If more than [count] attempts occur within an [expires] time frame for the given [cacheKey],
 *  subsequent requests are denied for a block window. The first block lasts [blocked]; each time the
 *  limit is hit again the block doubles (`blocked * 2^level`), capped at [maxBlocked].
 *
 *  The "strike level" is persisted under a separate key (`"$cacheKey-level"`) with a memory TTL far
 *  longer than any single block window (proportional to [maxBlocked]). This is what defeats a slow
 *  "popcorn" brute force: even after a block window lapses and the attacker returns for a fresh batch
 *  of [count] attempts, the remembered strike level makes the next block much longer. A **successful**
 *  action clears both the attempt counter and the strike level, so legitimate users are never
 *  penalized for a later mistake.
 *
 *  @param cacheKey The key you want to rate limit actions against.
 *  @param count The number of attempts that can be made in the [expires] time frame.
 *  @param expires The time frame for allowed attempts. Starts on the first attempt and resets every [expires] time passed.
 *  @param blocked The base block time frame applied the first time the limit is hit (level 0). Should be greater than or equal to [expires].
 *  @param maxBlocked The maximum block window; the exponentially-growing block is capped here. Should be greater than or equal to [blocked].
 *  @param action The action you want to make against the [cacheKey]
 *
 *  @throws [BadRequestException] if attempt is denied.
 */
context(server: ServerRuntime)
public suspend inline fun <R> Cache.constrainAttemptRate(
    cacheKey: String,
    count: Int = 5,
    expires: Duration = 5.minutes,
    blocked: Duration = expires,
    maxBlocked: Duration = 3.hours,
    action: () -> R,
): R {
    val startKey = "$cacheKey-start-time"
    val levelKey = "$cacheKey-level"

    val ct = (this.get<Int>(cacheKey) ?: 0)
    val start = this.setIfNotExists<Instant>(startKey, now(), expires)

    if (start && ct != 0 && ct <= count) {
        this.remove(cacheKey)
    }

    if (ct >= count) {
        val baseBlock = blocked.coerceAtLeast(expires)
        // Cap the exponent so the multiplication can never overflow to Duration.INFINITE; the result
        // is capped at maxBlocked long before level 20 anyway.
        val level = (this.get<Int>(levelKey) ?: 0)
        val block = (baseBlock * 2.0.pow(level.coerceAtMost(20))).coerceAtMost(maxBlocked.coerceAtLeast(baseBlock))
        // Remember the escalated strike level well beyond this block window so a returning attacker is
        // still treated as a repeat offender (defeats slow "popcorn" brute forcing).
        this.add(levelKey, 1, maxBlocked * 4)
        this.add(cacheKey, 1, block)
        throw BadRequestException("Too many attempts; please wait ${block.inWholeMinutes} minutes.")
    }

    return try {
        val result = action()
        remove(cacheKey)
        remove(levelKey)
        result
    } catch (e: Throwable) {
        this.add(cacheKey, 1, blocked.coerceAtLeast(expires))
        throw e
    }
}