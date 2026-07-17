package com.lightningkite.lightningserver.engine.local

import java.io.InputStream

/**
 * Thrown when a streamed request body exceeds the configured maximum size
 * (see [EngineReliabilitySettings.maxBodySize]).
 */
public class BodyTooLargeException : RuntimeException("Request body exceeded the configured maximum size.")

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
