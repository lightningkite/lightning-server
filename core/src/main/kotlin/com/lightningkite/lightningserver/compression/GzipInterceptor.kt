package com.lightningkite.lightningserver.compression

import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpInterceptor
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.gzip
import com.lightningkite.services.data.AbstractSuspendingSink
import com.lightningkite.services.data.AbstractSuspendingSource
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.SuspendingSink
import com.lightningkite.services.data.SuspendingSource
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.use
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.asOutputStream
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import kotlin.use

/** Staging array size for moving bytes between a [Buffer] and a [java.io.OutputStream]. */
private const val COPY_CHUNK: Int = 8 * 1024

public class GzipInterceptor: HttpInterceptor {
    context(runtime: ServerRuntime)
    override suspend fun intercept(
        request: HttpRequest<*>,
        cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse
    ): HttpResponse {
        val result = cont(request)

        val acceptedEncodings = request.headers.getMany(HttpHeader.AcceptEncoding)
        if (acceptedEncodings.isEmpty()) return result

        val accepts = acceptedEncodings
            .map { it.root.lowercase().substringBefore(';').trim() }

        // Accept-Encoding negotiation (gzip only for now)
        if (!accepts.contains("gzip")) return result

        // The handler already encoded this body (e.g. proxying an upstream that was itself compressed). Encoding it
        // again would produce a body no client unwraps twice.
        if (result.headers[HttpHeader.ContentEncoding] != null) return result

        val body = result.body ?: return result

        // Content-Type denylist (skip already-compressed types)
        if (body.mediaType.type in setOf("image", "audio", "video") ||
            (body.mediaType.type == "application" &&
                    body.mediaType.subtype in setOf("zip", "gzip", "x-gzip", "x-7z-compressed", "x-bzip2", "x-tar", "pdf")) ||
            (body.mediaType.type == "font" && body.mediaType.subtype in setOf("woff", "woff2"))
        ) return result

        // Lower compress limit. Either not worth the effort, or likely will inflate a little.
        if (body.data.size?.let { it < 256 } == true) return result

        // Stream-compress a body straight into the response through GZIP, with no full-body buffering. Runs
        // inside Data.Sink.emit, which engines invoke on a blocking-capable dispatcher, so the blocking GZIP
        // writes never touch an event loop.
        fun gzipStream(writePlain: (kotlinx.io.Sink) -> Unit): Data.Sink = Data.Sink { outSink ->
            // GZIPOutputStream.close() is what writes the trailer and releases the Deflater, but Data.Sink's contract
            // is that the emitter never closes the caller's sink — so shield the sink from that close.
            val gzip = GZIPOutputStream(NonClosingOutputStream(outSink.asOutputStream()))
            gzip.asSink().buffered().use { writePlain(it) }
        }
        val (newData, compressed) = when (val data = body.data) {
            // Push producer / blocking source: drive the plaintext straight into GZIP with no buffering, so a
            // large streamed response (e.g. an octet-stream/CSV/JSON download) is never materialized in heap.
            is Data.Sink -> gzipStream(data.emit) to true
            is Data.Source -> gzipStream { sink -> data.source.use { sink.transferFrom(it) } } to true

            // Cooperative streams compress incrementally too. Deflating writes into an in-memory buffer and never
            // blocks, so there is nothing to offload and no reason to materialize the body first.
            is Data.SuspendingSource -> Data.SuspendingSource(data.source.gzip()) to true
            is Data.SuspendingSink -> Data.SuspendingSink { it.gzip().use(data.emit) } to true

            else -> {
                // 1024 Grey area. It likely will compress fine, but if not send the original
                val s = data.size
                if (s?.let { it <= 1024 } == true) {
                    val og = data.bytes()
                    val gz = og.gzip()
                    if (gz.size < s)
                        Data.Bytes(gz) to true
                    else
                        Data.Bytes(og) to false
                } else
                    Data.Bytes(data.bytes().gzip()) to true
            }
        }
        return result.copy(
            headers = if (compressed) result.headers.copy {
                add(HttpHeader.ContentEncoding, "gzip")
                // Shared caches must not hand this compressed body to a client that never asked for gzip.
                add(HttpHeader.Vary, HttpHeader.AcceptEncoding)
                // Whatever length the handler declared describes the plaintext. Engines derive the real one from
                // the body, so drop the stale header rather than ship a mismatch.
                remove(HttpHeader.ContentLength)
            } else result.headers,
            body = TypedData(newData, body.mediaType)
        )
    }
}

/**
 * Wraps this source so consumers read the GZIP-compressed form of its bytes.
 *
 * Compression happens incrementally as the consumer pulls, so a stream of any size is never materialized. Deflating
 * writes into an in-memory buffer and never blocks, so the result is safe to consume on an engine event loop; the only
 * added cost is CPU.
 */
internal fun SuspendingSource.gzip(): SuspendingSource {
    val upstream = this
    val compressed = Buffer()
    val staging = Buffer()
    val chunk = ByteArray(COPY_CHUNK)
    val gzip = GZIPOutputStream(BufferOutputStream(compressed))
    var finished = false
    return object : AbstractSuspendingSource() {
        override suspend fun fill(into: Buffer): Long = withContext(Dispatchers.IO) {
            // Pull plaintext until deflate spills something, or the upstream ends and the trailer has been written.
            while (compressed.exhausted() && !finished) {
                if (upstream.read(staging) < 0L) {
                    // Only finish() writes the final deflate block plus the CRC32/ISIZE trailer; flush() never does.
                    gzip.finish()
                    finished = true
                } else {
                    gzip.writeFrom(staging, staging.size, chunk)
                    // Sync-flush so each batch reaches the consumer instead of sitting in deflate's internal buffer.
                    // Costs some ratio on finely-trickled sources; latency matters more for exactly those streams.
                    gzip.flush()
                }
            }
            if (compressed.exhausted()) -1L else compressed.transferTo(into)
        }

        override fun release(cause: Throwable?) {
            gzip.close() // releases the Deflater's native buffer; any trailer it writes here is discarded
            upstream.cancel(cause)
        }
    }
}

/**
 * Wraps this sink so plaintext written to the returned sink is GZIP-compressed before reaching this sink.
 *
 * Each write is deflated and forwarded incrementally. Explicit [SuspendingSink.flush] calls become sync-flush points,
 * so trickling streams stay responsive while bulk streams keep a full compression ratio. Blocking deflate operations
 * are dispatched to [Dispatchers.IO] so the caller's coroutine context is never blocked.
 */
internal fun SuspendingSink.gzip(): SuspendingSink {
    val out = this
    val compressed = Buffer()
    val chunk = ByteArray(COPY_CHUNK)
    val gzip = GZIPOutputStream(BufferOutputStream(compressed))
    return object : AbstractSuspendingSink() {
        override suspend fun write(from: Buffer) {
            checkWritable()
            withContext(Dispatchers.IO) {
                gzip.writeFrom(from, from.size, chunk)
            }
            // Forward only what deflate has already spilled on its own; forcing a flush here would wreck the
            // ratio for producers that write in small pieces.
            out.write(compressed)
        }

        override suspend fun flush() {
            checkWritable()
            withContext(Dispatchers.IO) {
                gzip.flush()
            }
            out.write(compressed)
            out.flush()
        }

        override suspend fun finish() {
            withContext(Dispatchers.IO) {
                gzip.finish() // final deflate block plus the CRC32/ISIZE trailer
            }
            out.write(compressed)
        }

        override fun release(cause: Throwable?) {
            gzip.close() // releases the Deflater's native buffer; any trailer it writes here is discarded
            cause?.let { out.cancel(it) }
        }
    }
}

/**
 * Copies [count] bytes out of [from] into this stream, consuming them. Staged through [chunk] so it is not limited to
 * the 2 GiB a single transfer could otherwise address.
 */
private fun OutputStream.writeFrom(from: Buffer, count: Long, chunk: ByteArray) {
    var remaining = count
    while (remaining > 0) {
        val read = from.readAtMostTo(chunk, 0, minOf(remaining, chunk.size.toLong()).toInt())
        require(read > 0) { "Asked to write $count bytes, but the buffer ran out $remaining bytes short" }
        write(chunk, 0, read)
        remaining -= read
    }
}

private class BufferOutputStream(val buffer: Buffer): OutputStream() {
    override fun write(b: Int) {
        buffer.writeByte(b.toByte())
    }

    override fun write(b: ByteArray) {
        buffer.write(b, 0, b.size)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        buffer.write(b, off, len)
    }
}

/** Passes writes through to [delegate] but downgrades [close] to a flush, leaving the delegate's lifecycle alone. */
private class NonClosingOutputStream(val delegate: OutputStream): OutputStream() {
    override fun write(b: Int): Unit = delegate.write(b)
    override fun write(b: ByteArray): Unit = delegate.write(b)
    override fun write(b: ByteArray, off: Int, len: Int): Unit = delegate.write(b, off, len)
    override fun flush(): Unit = delegate.flush()
    override fun close(): Unit = delegate.flush()
}
