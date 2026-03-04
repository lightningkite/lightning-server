package com.lightningkite.lightningserver.ratelimit

import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * Configuration for rate limiting on a specific endpoint or scope.
 *
 * Supports two algorithms:
 * - **Fixed window** (default): When [borrowTime] is null, allows [requests] per [window].
 * - **Token bucket**: When [borrowTime] is set, each request "borrows" that much time.
 *   Requests are rejected when accumulated debt exceeds [leeway].
 *
 * @param requests Maximum number of requests allowed within the [window] (fixed window mode)
 * @param window The time window for the rate limit (fixed window mode)
 * @param keyStrategy How to identify the client for rate limiting purposes
 * @param scope Optional shared scope name. Endpoints with the same scope share their rate limit counter.
 * @param borrowTime When non-null, enables token bucket mode. Each request borrows this duration.
 *   A higher rate multiplier reduces the effective borrow time, allowing more requests.
 * @param leeway Maximum accumulated debt allowed before rejecting requests (token bucket mode).
 */
@Serializable
public data class RateLimitConfig(
    val requests: Int = 1,
    val window: Duration = Duration.INFINITE,
    val keyStrategy: KeyStrategy = KeyStrategy.IP,
    val scope: String? = null,
    val borrowTime: Duration? = null,
    val leeway: Duration = Duration.ZERO,
)

/**
 * Strategy for generating rate limit keys to identify clients.
 */
@Serializable
public enum class KeyStrategy {
    /** Rate limit by source IP address */
    IP,
    /** Rate limit by authenticated user ID */
    USER,
    /** Rate limit by both IP and user ID combined */
    IP_AND_USER,
    /** Single shared limit across all requests */
    GLOBAL,
}
