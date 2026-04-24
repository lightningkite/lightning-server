package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import kotlinx.html.HTML
import kotlinx.html.html
import kotlinx.html.stream.appendHTML
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.writeString

/**
 * Creates an HTTP response that redirects to a GET request at the specified location (303 See Other).
 * This is typically used after a POST to redirect the user to view the created/modified resource.
 *
 * @param to The target URL path
 * @param headers Additional headers to include in the response
 * @return An HttpResponse with status 303 and Location header
 */
context(serverRuntime: ServerRuntime)
public fun HttpResponse.Companion.redirectToGet(to: String, headers: HttpHeaders = HttpHeaders.EMPTY): HttpResponse =
    HttpResponse(
        status = HttpStatus.SeeOther,
        headers = headers.copy { add(HttpHeader.Location, generalSettings().absolutePathAdjustment(to)) },
    )

/**
 * Creates an HTTP response indicating the resource has temporarily moved (307 Temporary Redirect).
 * The client should use the same HTTP method when following the redirect.
 *
 * @param to The target URL path
 * @param headers Additional headers to include in the response
 * @return An HttpResponse with status 307 and Location header
 */
context(serverRuntime: ServerRuntime)
public fun HttpResponse.Companion.pathMoved(to: String, headers: HttpHeaders = HttpHeaders.EMPTY): HttpResponse =
    HttpResponse(
        status = HttpStatus.TemporaryRedirect,
        headers = headers.copy { add(HttpHeader.Location, generalSettings().absolutePathAdjustment(to)) },
    )

/**
 * Creates an HTTP response indicating the resource has moved (302 Found).
 * This is the older, less precise version of temporary redirect. Consider using [pathMoved] instead.
 *
 * @param to The target URL path
 * @param headers Additional headers to include in the response
 * @return An HttpResponse with status 302 and Location header
 */
context(serverRuntime: ServerRuntime)
public fun HttpResponse.Companion.pathMovedOld(to: String, headers: HttpHeaders = HttpHeaders.EMPTY): HttpResponse =
    HttpResponse(
        status = HttpStatus.Found,
        headers = headers.copy { add(HttpHeader.Location, generalSettings().absolutePathAdjustment(to)) },
    )

/**
 * Creates an HTTP response indicating the resource has permanently moved (308 Permanent Redirect).
 * Clients should update bookmarks and search engines should update their indexes.
 *
 * @param to The target URL path
 * @param headers Additional headers to include in the response
 * @return An HttpResponse with status 308 and Location header
 */
context(serverRuntime: ServerRuntime)
public fun HttpResponse.Companion.pathMovedPermanently(
    to: String,
    headers: HttpHeaders = HttpHeaders.EMPTY,
): HttpResponse = HttpResponse(
    status = HttpStatus.PermanentRedirect,
    headers = headers.copy { add(HttpHeader.Location, generalSettings().absolutePathAdjustment(to)) },
)

/**
 * Creates an HTTP response with HTML content using a DSL builder.
 *
 * @param status The HTTP status code (defaults to 200 OK)
 * @param headers Additional headers to include in the response
 * @param builder A lambda to build the HTML content using kotlinx.html DSL
 * @return An HttpResponse with HTML content
 */
public fun HttpResponse.Companion.html(
    status: HttpStatus = HttpStatus.OK,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    builder: HTML.() -> Unit,
): HttpResponse = HttpResponse(
    body = TypedData.html(builder),
    status = status,
    headers = headers
)

/**
 * Creates an HTTP response with HTML content from a raw string.
 *
 * @param status The HTTP status code (defaults to 200 OK)
 * @param headers Additional headers to include in the response
 * @param content The raw HTML string
 * @return An HttpResponse with HTML content
 */
public fun HttpResponse.Companion.html(
    status: HttpStatus = HttpStatus.OK,
    headers: HttpHeaders = HttpHeaders.EMPTY,
    content: String,
): HttpResponse = HttpResponse(
    body = TypedData.text(content, MediaType.Text.Html),
    status = status,
    headers = headers
)

/**
 * Creates an HTTP response with plain text content.
 *
 * @param text The plain text content
 * @param status The HTTP status code (defaults to 200 OK)
 * @param headers Additional headers to include in the response
 * @return An HttpResponse with plain text content
 */
public fun HttpResponse.Companion.plainText(
    text: String,
    status: HttpStatus = HttpStatus.OK,
    headers: HttpHeaders = HttpHeaders.EMPTY,
): HttpResponse = HttpResponse(
    body = TypedData.text(text, MediaType.Text.Plain),
    status = status,
    headers = headers
)


/**
 * Creates typed data containing HTML generated from a DSL builder.
 *
 * @param body A lambda to build the HTML content using kotlinx.html DSL
 * @return TypedData with HTML media type
 */
public fun TypedData.Companion.html(
    body: HTML.() -> Unit,
): TypedData = TypedData.sink(
    mediaType = MediaType.Text.Html,
    emit = { sink ->
        val appendable = object : Appendable {
            override fun append(csq: CharSequence): Appendable {
                sink.writeString(csq)
                return this
            }

            override fun append(c: Char): Appendable {
                sink.writeString(c.toString())
                return this
            }

            override fun append(csq: CharSequence, start: Int, end: Int): Appendable {
                sink.writeString(csq, start, end)
                return this
            }
        }
        appendable.appendHTML().html(block = body)
    }
)

//public inline fun <reified T> TypedData.Companion.json(
//    value: T,
//): TypedData = TypedData.text(
//    text = Serialization.json.encodeToString(value),
//    mediaType = MediaType.Application.Json
//)

/**
 * Creates typed data from a file path.
 * The media type is automatically determined from the file extension if not specified.
 *
 * @param path The path to the file
 * @param fileSystem The file system to read from
 * @param type The media type (defaults to type inferred from file extension)
 * @return TypedData containing the file contents
 * @throws NullPointerException if the file metadata cannot be retrieved (file doesn't exist)
 */
// TODO: Replace !! with proper error handling and throw a more descriptive exception if file doesn't exist
public fun TypedData.Companion.path(
    path: Path,
    fileSystem: FileSystem,
    type: MediaType = MediaType.fromExtension(path.name.substringAfterLast('.')),
): TypedData {
    return TypedData.source(
        source = fileSystem.source(path).buffered(),
        mediaType = type,
        size = fileSystem.metadataOrNull(path)!!.size
    )
}

//context(serverRunning: ServerRunning)
//public inline fun <reified T> HttpResponse.Companion.json(
//    value: T,
//    status: HttpStatus = HttpStatus.OK,
//    headers: HttpHeaders.Builder.() -> Unit = {}
//) = HttpResponse(
//    body = TypedData.json(value),
//    status = status,
//    headers = HttpHeaders(headers)
//)

/*
 * TODO: API Recommendations
 *
 * 1. Consider removing or uncommenting the commented-out json() functions, or document why they're commented
 * 2. The path() function should handle the case where fileMetadataOrNull returns null more gracefully
 * 3. Consider adding HttpResponse.Companion.json() as an active function (currently commented out)
 * 4. Consider standardizing parameter order across similar functions (some have headers before body params, some after)
 */