package com.lightningkite.lightningserver.engine.local

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.Engine
import java.io.InputStream

/**
 * Thrown when a streamed request body exceeds the configured maximum size
 * (see [EngineReliabilitySettings.maxBodySize]).
 *
 * A body is read lazily, so this is thrown from inside whichever handler reads it. Being an
 * [HttpStatusException], it is answered with 413 Payload Too Large like any other status exception,
 * with no engine involvement. Engines additionally remember that a body went over the limit and
 * answer 413 even when a handler catches this and carries on.
 */
public class BodyTooLargeException : HttpStatusException(
    status = HttpStatus.PayloadTooLarge,
    detail = "payload-too-large",
    message = "Request body exceeded the configured maximum size.",
)

/**
 * Whether an HTTP/1.x request carries a body, judged from its headers alone.
 *
 * HTTP/1.x frames a request body in exactly two ways, a positive `Content-Length` or chunked
 * encoding, so the headers settle it. Engines use this to give a bodyless request a null body,
 * with nothing set up to read one. A body that passes this check can still turn out to be empty,
 * which readers treat the same as no body.
 *
 * @param contentLength the declared `Content-Length`, or null if there is none.
 * @param transferEncoding every `Transfer-Encoding` header line the request carries. Chunked
 *   encoding may be named on any of them.
 */
public fun hasRequestBody(contentLength: Long?, transferEncoding: List<String>): Boolean =
    (contentLength != null && contentLength > 0) ||
            transferEncoding.any { it.contains("chunked", ignoreCase = true) }

/**
 * The response to send for a request whose body may have gone over the size limit.
 *
 * A body that passed the cap mid-read is a 413. Normally the handler's read threw
 * [BodyTooLargeException] and that is already what came back; this covers a handler that caught it
 * and went on. The handler's own response is then thrown away, so its body is closed here: a
 * streamed body may hold a file handle or a database cursor.
 *
 * The CORS headers on the handler's response are kept, so a browser lets the page see the 413
 * instead of reporting an opaque network error. The rest belong to the response being replaced.
 *
 * @param handled what the handler returned.
 * @param exceeded whether the request body went over the limit while being read.
 */
context(engine: Engine)
public fun enforceBodyLimit(handled: HttpResponse, exceeded: Boolean): HttpResponse {
    if (!exceeded || handled.status == HttpStatus.PayloadTooLarge) return handled
    try {
        handled.body?.data?.close()
    } catch (e: Exception) {
        engine.logger.warn(e) { "Could not close a response body replaced by 413 Payload Too Large" }
    }
    val cors = handled.headers.normalizedEntries
        .filterKeys { it.startsWith("access-control-") || it == "vary" }
        .flatMap { (key, values) -> values.map { key to it.toHttpString() } }
    return HttpResponse.plainText("Payload Too Large", HttpStatus.PayloadTooLarge, HttpHeaders(cors))
}

/**
 * Copies [input] to [write] in bounded 32 KiB chunks, throwing [BodyTooLargeException] as soon as more
 * than [maxBytes] have been read — so an oversized body (including chunked / unknown-length bodies) is
 * aborted before it is fully buffered.
 *
 * Shared by the HTTP engines so the max-body enforcement lives in one place rather than being duplicated
 * (and high-risk) in each adapter. It must happen at this streamed-read layer because by the time a
 * handler runs the body may already be buffered. [write] receives `(buffer, offset, length)` and is the
 * engine's sink write.
 */
public inline fun copyLimited(input: InputStream, maxBytes: Long, write: (ByteArray, Int, Int) -> Unit) {
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maxBytes) throw BodyTooLargeException()
        write(buffer, 0, read)
    }
}
