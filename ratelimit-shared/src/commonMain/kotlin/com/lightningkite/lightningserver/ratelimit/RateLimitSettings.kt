package com.lightningkite.lightningserver.ratelimit

import kotlinx.serialization.Serializable

/**
 * Global rate limiting settings, configurable via settings.json.
 *
 * @param enabled Whether rate limiting is active. When false, all requests pass through.
 * @param defaultLimit Optional default rate limit applied to all endpoints that don't have an explicit limit.
 * @param headerPrefix Prefix for rate limit response headers (e.g., "X-RateLimit-").
 */
@Serializable
public data class RateLimitSettings(
    val enabled: Boolean = true,
    val defaultLimit: RateLimitConfig? = null,
    val headerPrefix: String = "X-RateLimit-",
)
