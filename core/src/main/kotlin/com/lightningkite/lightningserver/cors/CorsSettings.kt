package com.lightningkite.lightningserver.cors

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


/**
 * Configuration for Cross-Origin Resource Sharing (CORS) handling.
 *
 * Controls how the server responds to cross-origin requests by managing CORS headers
 * and origin validation.
 *
 * ## Domain Matching
 * The [limitToDomains] field supports wildcard subdomain matching:
 * - `*.example.com` (no scheme) matches any subdomain with any scheme
 * - `*` matches all origins (not recommended for production)
 *
 * When a match occurs, the actual request Origin is returned in the Access-Control-Allow-Origin
 * header (not the pattern).
 **
 * ## Credentials Warning
 * When [allowCredentials] is `true`, you cannot use wildcard origins (`*`) per CORS spec.
 * You must specify exact origins or patterns. The implementation does not enforce this
 * constraint - it's the developer's responsibility.
 *
 * @param limitToDomains Allowed origins for CORS. Compared against the incoming `Origin` header.
 *      When matched, the request's `Origin` is reflected in `Access-Control-Allow-Origin`.
 *      - single element list with a '*' = do not limit - mirror all origins (permissive)
 *      - empty list = no origins allowed (default, restrictive)
 *      - list of patterns = only matching origins allowed
 * @param limitToHeaders Allowed request headers. Placed directly into `Access-Control-Allow-Headers`.
 *      - single element list with a '*' = do not limit - mirror `Access-Control-Request-Headers` from request
 *      - empty list = no additional headers allowed (default)
 * @param limitToMethods Allowed HTTP methods. Placed directly into `Access-Control-Allow-Methods`.
 *      - single element list with a '*' = do not limit - mirror `Access-Control-Request-Method` from request
 *      - empty list = no methods allowed (default)
 * @param exposedHeaders Response headers exposed to the client beyond CORS-safe headers.
 *      Placed into `Access-Control-Expose-Headers`. Empty list means only safe headers are exposed.
 * @param allowCredentials Whether to allow credentials (cookies, authorization headers, TLS certificates).
 *      Sets `Access-Control-Allow-Credentials: true` when enabled. Default is `false`.
 * @param cacheLength Duration in seconds to cache preflight responses. Sets `Access-Control-Max-Age`.
 *      - `null` = no caching header sent (default)
 *      - value = seconds to cache
 * @param forbidOnMatchFail When `true`, requests with non-matching `Origin` headers receive 403 Forbidden
 *      immediately, before endpoint processing. When `false`, requests continue but CORS headers are omitted.
 *      **Note**: WebSocket connections always fail on origin mismatch regardless of this setting.
 */
@Serializable
public data class CorsSettings(
    val limitToDomains: List<String> = emptyList(),
    val limitToHeaders: List<String> = emptyList(),
    val limitToMethods: List<String> = emptyList(),
    val exposedHeaders: List<String> = emptyList(),
    val allowCredentials: Boolean = false,
    val cacheLength: Duration? = null,
    val forbidOnMatchFail: Boolean = true,
) {
    public companion object {
        /**
         * Creates a permissive CORS configuration suitable for development.
         *
         * **WARNING**: This configuration should NEVER be used in production as it:
         * - Allows all origins (*)
         * - Allows all headers (*)
         * - Allows all methods (*)
         * - Enables credentials with wildcard origins (violates CORS spec)
         * - Does not reject mismatched origins
         *
         * @return A CorsSettings instance that allows all cross-origin requests
         */
        public fun allowAll(): CorsSettings = CorsSettings(
            limitToDomains = listOf("*"),
            limitToHeaders = listOf("*"),
            limitToMethods = listOf("*"),
            allowCredentials = true,
            cacheLength = 10.seconds,
            forbidOnMatchFail = false,
        )

        /**
         * Creates a CORS configuration suitable for production with specific origins.
         *
         * This configuration:
         * - Restricts origins to the specified list
         * - Allows all headers and methods (convenient but permissive)
         * - Enables credentials
         * - Caches preflight responses for 10 seconds
         * - Rejects requests from non-matching origins
         *
         * @param origins Allowed origin URLs (e.g., "https://example.com", "*.example.com")
         * @return A CorsSettings instance configured for production use
         */
        public fun forProduction(vararg origins: String): CorsSettings = CorsSettings(
            limitToDomains = origins.toList(),
            limitToHeaders = listOf("*"),
            limitToMethods = listOf("*"),
            allowCredentials = true,
            cacheLength = 10.seconds,
        )
    }
}

// TODO: API Recommendations
// 1. Add validation in init block to check allowCredentials + wildcard origins combination
//    This violates the CORS spec and can lead to browser errors
// 2. Consider adding a restrictive() factory for highly secure defaults
// 3. Add documentation examples showing common use cases (SPA + API, mobile app, etc.)
// 4. Consider adding domain validation to catch typos (e.g., missing scheme where required)
// 5. The cacheLength uses UInt which might be surprising - consider using Duration instead
// 6. Add a copy() convenience method that validates settings after modification
