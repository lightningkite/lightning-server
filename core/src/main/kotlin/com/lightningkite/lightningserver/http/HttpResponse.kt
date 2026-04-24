package com.lightningkite.lightningserver.http

import com.lightningkite.services.data.TypedData

/**
 * Represents an HTTP response with body, status code, and headers.
 *
 * This is the return type for all HTTP handlers. The status defaults to 200 OK if a body
 * is provided, or 204 No Content if the body is null.
 *
 * Example:
 * ```kotlin
 * HttpResponse(
 *     body = TypedData.text("Hello"),
 *     status = HttpStatus.OK,
 *     headers = HttpHeaders { set("X-Custom", "value") }
 * )
 * ```
 *
 * Use extension functions on the companion object for common response types:
 * - `HttpResponse.plainText("text")`
 * - `HttpResponse.json(obj)`
 * - `HttpResponse.html { ... }`
 * - `HttpResponse.redirectToGet("url")`
 *
 * @property body The response body as TypedData, or null for no body
 * @property status The HTTP status code (defaults based on body presence)
 * @property headers The HTTP headers to include in the response
 */
public data class HttpResponse(
    public val body: TypedData? = null,
    public val status: HttpStatus = if (body != null) HttpStatus.OK else HttpStatus.NoContent,
    public val headers: HttpHeaders = HttpHeaders.Companion.EMPTY,
) {
    public companion object
}

/*
 * TODO: API Recommendations for HttpResponse.kt
 *
 * 1. The default status calculation in the constructor parameter could be surprising when
 *    explicitly setting status with a body. Consider documenting this behavior more clearly
 *    or requiring explicit status when body is provided.
 *
 * 2. Add convenience methods directly on HttpResponse for common modifications:
 *    - fun withHeader(name: String, value: String): HttpResponse
 *    - fun withStatus(status: HttpStatus): HttpResponse
 *    - fun withCookie(cookie: Cookie): HttpResponse
 *
 * 3. Consider validation to catch common mistakes:
 *    - Warn/error if body is present for 204 No Content
 *    - Warn/error if body is missing for 200 OK
 *    - Validate that redirect status codes (3xx) have Location header
 *
 * 4. Add a method to check if the response is cacheable based on status and headers:
 *    - val cacheable: Boolean
 *
 * 5. The companion object is empty - consider moving the extension functions (plainText, json, etc.)
 *    to be members of the companion object for better discoverability via autocomplete.
 */
