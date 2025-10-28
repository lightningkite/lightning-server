package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.ServerRuntime
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
