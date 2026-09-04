package com.lightningkite.lightningserver.data

import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.ServerRuntime

/**
 * Base class for HTTP requests in Lightning Server.
 *
 * Provides access to request metadata (headers, query parameters, path, etc.)
 * and includes a built-in cache for computed values via the [Caching] interface.
 *
 * This is typically implemented by the framework and passed to endpoint handlers.
 *
 * What is *not* here is any identifier of ours: correlation lives on
 * [com.lightningkite.lightningserver.runtime.ServerRuntime.initiator], because a task or a schedule
 * tick has no request to hang it on. The two identifiers that remain below are wire-level facts
 * about the caller and the gateway, not identifiers the server minted.
 *
 * @param PATH The path specification type for this request
 */
public abstract class Request<out PATH : PathSpec> : HasContextualPath<PATH>, Caching {
    /** The resolved path information for this request. */
    public abstract val path: HasContextualPath<PATH>

    /** Query string parameters parsed from the URL. */
    public abstract val queryParameters: QueryParameters

    /** HTTP headers from the request. */
    public abstract val headers: HttpHeaders

    /** The domain/host name from the request (e.g., "example.com"). */
    public abstract val domain: String

    /** The protocol used for the request (e.g., "http" or "https"). */
    public abstract val protocol: String

    /** The source IP address of the client making the request. */
    public abstract val sourceIp: String

    /**
     * An identifier the caller supplied that was not trusted, kept for diagnostics only.
     * Never used for correlation.
     */
    public abstract val upstreamRequestId: String?

    /**
     * The identifier the gateway or proxy in front of the server minted for this request, or null
     * when the engine has none. Trusted — it comes from our own infrastructure rather than the
     * caller — and kept only so a record can be joined back to that gateway's own access log, which
     * is why it is a `String`: it is the gateway's identifier, in whatever shape the gateway uses.
     */
    public abstract val engineRequestId: String?

    context(serverRuntime: ServerRuntime)
    override val pathInContext: ResolvedPath<PATH>
        get() = path.pathInContext
}

/**
 * Retrieves or calculates a cached value for this request.
 *
 * Uses the request's built-in cache to avoid recomputing expensive operations.
 * If the value is not cached, it will be calculated using the key's calculation function.
 *
 * @param key The cache key with calculation logic
 * @return The cached or newly calculated value
 */
context(server: ServerRuntime)
public suspend operator fun <T> Request<*>.get(key: SerializableCache.CalculatingKey<Request<*>, T>): T {
    return cache.get(key, this)
}

/*
 * TODO: API Recommendations for Request.kt
 *
 * 1. Consider adding convenience accessors for common operations:
 *    - val fullUrl: String (protocol + domain + path)
 *    - val isSecure: Boolean (protocol == "https")
 *    - val origin: String (protocol + domain)
 *
 * 2. The sourceIp field doesn't account for proxies/load balancers.
 *    Consider adding realIp that checks X-Forwarded-For or similar headers.
 *
 * 3. Add utility methods for common header checks:
 *    - fun accepts(mediaType: MediaType): Boolean
 *    - fun isAjax(): Boolean (X-Requested-With header check)
 *
 * 4. Consider adding a typed body accessor pattern to avoid repetitive deserialization code
 *
 * 5. The Request class could benefit from a toString() implementation for logging/debugging
 */
