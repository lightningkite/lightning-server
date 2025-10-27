package com.lightningkite.lightningserver.data

import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Wraps a value with an optional expiration time.
 *
 * Useful for caching values that should expire after a certain period.
 * The expiration check is performed relative to the server's clock.
 *
 * Example:
 * ```kotlin
 * with(serverRuntime) {
 *     val cached = Expiring("cached data", expireAfter = 5.minutes)
 *     if (!cached.expired) {
 *         // Use cached.value
 *     }
 * }
 * ```
 *
 * @param T The type of the wrapped value
 * @property value The wrapped value
 * @property expiresAt The absolute expiration time, or null if the value never expires
 */
@Serializable
public data class Expiring<T>(
    val value: T,
    val expiresAt: Instant?
) {
    /**
     * Returns true if the value has expired based on the server's current time.
     *
     * A value is considered expired if [expiresAt] is non-null and less than or equal to the current time.
     *
     * Note: Requires ServerRuntime context to access the server's clock.
     */
    context(server: ServerRuntime)
    public val expired: Boolean get() = expiresAt != null && expiresAt <= server.clock.now()
}

/**
 * Creates an [Expiring] wrapper with a relative expiration duration.
 *
 * @param value The value to wrap
 * @param expireAfter Duration after which the value expires, or null for no expiration
 * @return An Expiring instance with expiresAt set to now + expireAfter
 *
 * Note: Requires ServerRuntime context to access the server's clock for calculating expiration time.
 */
context(server: ServerRuntime)
public fun <T> Expiring(value: T, expireAfter: Duration?): Expiring<T> = Expiring(value, expireAfter?.let { server.clock.now() + it })

/*
 * TODO: API Recommendations for Expiring.kt
 *
 * 1. Add a method to refresh/extend expiration:
 *    - context(server: ServerRuntime) fun extend(by: Duration): Expiring<T>
 *
 * 2. Consider adding a timeRemaining property:
 *    - context(server: ServerRuntime) val timeRemaining: Duration?
 *    Returns null if never expires, negative if expired, positive if time remaining
 *
 * 3. Add a non-context version that accepts an Instant directly for testing:
 *    - fun isExpired(at: Instant): Boolean
 */