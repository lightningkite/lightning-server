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
public data class HttpRequest<PATH: PathSpec>(
    override val path: RawHttpEndpoint<PATH>,
    override val queryParameters: QueryParameters,
    override val headers: HttpHeaders,
    override val domain: String,
    override val protocol: String,
    override val sourceIp: String,
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
    public fun <PATH2: PathSpec> copyWithNewPathType(
        path: RawHttpEndpoint<PATH2>,
        queryParameters: QueryParameters = this.queryParameters,
        headers: HttpHeaders = this.headers,
        domain: String = this.domain,
        protocol: String = this.protocol,
        sourceIp: String = this.sourceIp,
        cache: SerializableCache = this.cache,
        body: TypedData? = this.body,
    ): HttpRequest<PATH2> = HttpRequest(
        path = path,
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        cache = cache,
        body = body,
    )
}