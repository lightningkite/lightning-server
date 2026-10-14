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
 * Returns `true` only for the first caller to claim [cacheKey], and `false` for every later one.
 *
 * The claim is atomic on every backend, so concurrent callers race safely and exactly one wins. This makes one-time secrets such as signed proofs, TOTP codes and WebAuthN challenges single-use within their validity window.
 *
 * @param cacheKey Identifies the thing being consumed, and must be derived from the secret rather than the user.
 * @param ttl How long the claim is remembered, at least the remaining validity of the secret.
 */
context(server: ServerRuntime)
public suspend fun Cache.claimOnce(cacheKey: String, ttl: Duration): Boolean =
    setIfNotExists(cacheKey, now(), ttl)

/**
 * Represents the rejection [constrainAttemptRate] raises when a key has run out of attempts.
 *
 * It is a subclass of [BadRequestException] with the same status, detail and message, so callers can tell a rate-limited attempt from a wrong credential even though the client cannot.
 */
public class TooManyAttemptsException(message: String) : BadRequestException(message = message)

/**
 * Runs [action] against [cacheKey], denying it with exponential backoff once more than [count] attempts occur within [expires].
 *
 * The first block lasts [blocked], and each further time the limit is hit the block doubles, up to [maxBlocked]. The strike level is remembered for much longer than any block, so an attacker returning after a block lapses gets a longer one. A successful [action] clears the attempt counter and the strike level.
 *
 * @param cacheKey The key to rate limit actions against.
 * @param count The number of attempts allowed in the [expires] time frame.
 * @param expires The time frame for allowed attempts, which starts on the first attempt.
 * @param blocked The block time applied the first time the limit is hit, at least [expires].
 * @param maxBlocked The maximum block time, at least [blocked].
 * @throws BadRequestException if the attempt is denied.
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

    // The block window must never be shorter than the attempt window, or a blocked attacker could
    // simply wait out the block and immediately get a fresh batch of attempts. This is the block
    // duration for a first-time offender (strike level 0); repeat offenses multiply it below.
    val baseBlockDuration = blocked.coerceAtLeast(expires)

    val failedAttempts = (this.get<Int>(cacheKey) ?: 0)
    val windowStarted = this.setIfNotExists<Instant>(startKey, now(), expires)

    if (windowStarted && failedAttempts != 0 && failedAttempts < count) {
        this.remove(cacheKey)
        this.remove(levelKey)
    }

    if (failedAttempts >= count) {
        // Each repeat violation doubles the block: strike level 0 -> baseBlockDuration, level 1 -> x2,
        // level 2 -> x4, and so on, capped at maxBlockDuration.
        val strikeLevel = (this.get<Int>(levelKey) ?: 0).coerceAtMost(20) // avoid overflow to Duration.INFINITE
        val maxBlockDuration = maxBlocked.coerceAtLeast(baseBlockDuration)
        val blockDuration = (baseBlockDuration * 2.0.pow(strikeLevel)).coerceAtMost(maxBlockDuration)

        // Remember the escalated strike level well beyond this block window so a returning attacker is
        // still treated as a repeat offender (defeats slow "popcorn" brute forcing).
        this.add(levelKey, 1, maxBlocked * 4)
        this.add(cacheKey, 1, blockDuration)
        throw TooManyAttemptsException("Too many attempts; please wait ${blockDuration.inWholeMinutes} minutes.")
    }

    return try {
        val result = action()
        if (failedAttempts != 0) {
            // this technically is "incorrect", if user has failedAttempts=0 but level=5 because of above comment this won't reset level,
            // I think it's a worthwhile trade-off to make the happy path faster while maintaining security, this is just unforgiving
            remove(cacheKey)
            remove(levelKey)
        }
        result
    } catch (e: Throwable) {
        this.add(cacheKey, 1, baseBlockDuration)
        throw e
    }
}