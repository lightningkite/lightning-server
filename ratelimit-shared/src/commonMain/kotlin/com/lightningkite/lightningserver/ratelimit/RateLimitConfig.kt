package com.lightningkite.lightningserver.ratelimit

import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * Configuration for rate limiting on a specific endpoint or scope.
 *
 * @param requests Maximum number of requests allowed within the [window]
 * @param window The time window for the rate limit
 * @param keyStrategy How to identify the client for rate limiting purposes
 * @param scope Optional shared scope name. Endpoints with the same scope share their rate limit counter.
 */
@Serializable
public data class RateLimitConfig(
    val requests: Int,
    val window: Duration,
    val keyStrategy: KeyStrategy = KeyStrategy.IP,
    val scope: String? = null,
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
