package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.services.data.TypedData
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Represents an HTTP request with all associated data.
 *
 * This is the input type for all HTTP handlers. It extends [Request] and provides access to
 * path arguments, query parameters, headers, body, and request metadata.
 *
 * Example usage in a handler:
 * ```kotlin
 * val endpoint = path.path("users").arg<String>("id").get bind HttpHandler { request ->
 *     val userId = request.path.arg1 // Access path argument
 *     val filter = request.queryParameters["filter"] // Query parameter
 *     val auth = request.headers[HttpHeader.Authorization] // Header
 *     val bodyData = request.body?.text() // Request body
 *     // ...
 * }
 * ```
 *
 * @param PATH The PathSpec type describing the structure of this endpoint's path
 * @property path The resolved path with typed arguments
 * @property queryParameters The query string parameters
 * @property headers The HTTP request headers
 * @property domain The domain name used in the request (e.g., "example.com")
 * @property protocol The protocol used ("http" or "https")
 * @property sourceIp The originating IP address of the client
 * @property cache A cache for storing request-scoped data across interceptors
 * @property body The request body as TypedData, or null if no body
 */
@Serializable
public data class HttpRequest<PATH : PathSpec>(
    override val path: RawHttpEndpoint<PATH>,
    override val queryParameters: QueryParameters,
    override val headers: HttpHeaders,
    override val domain: String,
    override val protocol: String,
    override val sourceIp: String,
    override val requestId: String,
    override val parentRequestId: String? = null,
    override val upstreamRequestId: String? = null,
    override val cache: SerializableCache = SerializableCache(),
    @Transient public val body: TypedData? = null,
) : Request<PATH>() {
    /**
     * Creates a copy of this request with a different path type.
     *
     * This is primarily used internally when routing to convert from a generic path type
     * to a more specific one. Most user code will not need this function.
     *
     * @param PATH2 The new PathSpec type
     * @param path The new path with the updated type
     * @return A new HttpRequest with the specified path type
     */
    public fun <PATH2 : PathSpec> copyWithNewPathType(
        path: RawHttpEndpoint<PATH2>,
        queryParameters: QueryParameters = this.queryParameters,
        headers: HttpHeaders = this.headers,
        domain: String = this.domain,
        protocol: String = this.protocol,
        sourceIp: String = this.sourceIp,
        requestId: String = this.requestId,
        parentRequestId: String? = this.parentRequestId,
        upstreamRequestId: String? = this.upstreamRequestId,
        cache: SerializableCache = this.cache,
        body: TypedData? = this.body,
    ): HttpRequest<PATH2> = HttpRequest(
        path = path,
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        requestId = requestId,
        parentRequestId = parentRequestId,
        upstreamRequestId = upstreamRequestId,
        cache = cache,
        body = body,
    )

    /**
     * Derives a sub-request of this one, as dispatched by a multiplexed request such as `/meta/bulk`.
     *
     * The sub-request gets its own [requestId] with [parentRequestId] pointing back here, so each
     * logical request is independently attributable while remaining joinable to the request that
     * carried it.
     */
    public fun <PATH2 : PathSpec> subRequest(
        path: RawHttpEndpoint<PATH2>,
        queryParameters: QueryParameters = this.queryParameters,
        body: TypedData? = this.body,
    ): HttpRequest<PATH2> = copyWithNewPathType(
        path = path,
        queryParameters = queryParameters,
        body = body,
        requestId = generateRequestId(),
        parentRequestId = this.requestId,
    )
}

/*
 * TODO: API Recommendations for HttpRequest.kt
 *
 * 1. The @Transient annotation on body means it won't be serialized, which could cause
 *    issues if someone tries to serialize a request. Consider documenting this limitation
 *    or providing a warning in the class KDoc.
 *
 * 2. Add convenience extension functions for common body operations:
 *    - suspend fun HttpRequest<*>.textBody(): String
 *    - suspend fun <T> HttpRequest<*>.jsonBody(serializer: KSerializer<T>): T
 *    - fun HttpRequest<*>.requireBody(): TypedData (throws if null)
 *
 * 3. Consider adding a toString() method that doesn't include the full body content
 *    for logging purposes (to avoid logging sensitive data or large payloads).
 *
 * 4. The cache is mutable but shared across request copies, which could lead to
 *    unexpected behavior if not documented clearly. Consider adding a warning about
 *    cache sharing in copyWithNewPathType documentation.
 */