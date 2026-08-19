package com.lightningkite.lightningserver.compression

import com.lightningkite.lightningserver.http.ConnectionInterceptor
import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpResponse

import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.runtime.ungzip
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.data.AbstractSuspendingSource
import com.lightningkite.services.data.BufferSuspendingSink
import com.lightningkite.services.data.Data
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.SuspendingSink
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.asSuspendingSource
import com.lightningkite.services.data.readRemaining
import com.lightningkite.services.data.use
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the streaming gzip adapters and the interceptor that installs them. Every assertion decompresses the result,
 * because the failure mode these adapters are prone to — a stream missing its trailer — still looks like plausible
 * bytes until something actually tries to inflate it.
 */
class GzipInterceptorTest {

    /** A source that hands back one prepared chunk per fill, so tests control the read cadence exactly. */
    private class ChunkSource(
        chunks: List<ByteArray>,
        private val failAfter: Int = Int.MAX_VALUE,
    ) : AbstractSuspendingSource() {
        private val remaining = ArrayDeque(chunks)
        var delivered: Int = 0
            private set
        var releaseCause: Throwable? = null
            private set
        var released: Boolean = false
            private set

        override suspend fun fill(into: Buffer): Long {
            if (delivered >= failAfter) throw IllegalStateException("upstream blew up")
            val next = remaining.removeFirstOrNull() ?: return -1L
            into.write(next)
            delivered++
            return next.size.toLong()
        }

        override fun release(cause: Throwable?) {
            released = true
            releaseCause = cause
        }
    }

    private fun chunksOf(text: String, size: Int): List<ByteArray> =
        text.toByteArray().toList().chunked(size).map { it.toByteArray() }

    // A payload big enough to span several deflate blocks, with enough structure to actually compress.
    private val payload = (0 until 5000).joinToString("\n") { "line $it: the quick brown fox jumps over the lazy dog" }

    @Test
    fun `source gzip round-trips a multi-block stream`() = runBlocking {
        val original = payload.toByteArray()
        val compressed = ChunkSource(chunksOf(payload, 700)).gzip().readRemaining().readByteArray()

        assertContentEquals(original, compressed.ungzip())
        assertTrue(compressed.size < original.size, "expected compression, got ${compressed.size} vs ${original.size}")
    }

    @Test
    fun `source gzip round-trips an empty stream`() = runBlocking {
        val compressed = ChunkSource(listOf()).gzip().readRemaining().readByteArray()

        assertContentEquals(ByteArray(0), compressed.ungzip())
    }

    @Test
    fun `source gzip forwards bytes before the upstream ends`() = runBlocking {
        val source = ChunkSource(chunksOf(payload, 700))
        val gzipped = source.gzip()

        // One pull for whatever is available; a stream that only emitted at EOF would have drained the upstream.
        val first = Buffer()
        gzipped.read(first)

        assertTrue(first.size > 0, "expected compressed bytes from the first read")
        assertTrue(source.delivered < 3, "expected a trickle, but the upstream was drained ${source.delivered} times")
        gzipped.cancel()
    }

    @Test
    fun `source gzip surfaces an upstream failure and releases the upstream`() = runBlocking {
        val source = ChunkSource(chunksOf(payload, 700), failAfter = 2)
        val gzipped = source.gzip()

        assertFailsWith<IllegalStateException> { gzipped.readRemaining() }
        // A failed compressed stream must not come back as a clean end on the next read.
        assertFailsWith<IllegalStateException> { gzipped.read(Buffer()) }
        assertTrue(source.released, "upstream should have been released")
    }

    @Test
    fun `source gzip cancel releases the upstream`() = runBlocking {
        val source = ChunkSource(chunksOf(payload, 700))
        val gzipped = source.gzip()
        gzipped.read(Buffer())

        val cause = IllegalStateException("client hung up")
        gzipped.cancel(cause)

        assertTrue(source.released, "upstream should have been released")
        assertEquals(cause, source.releaseCause)
    }

    @Test
    fun `producer gzip round-trips a multi-block stream`() = runBlocking {
        val original = payload.toByteArray()
        val producer = Data.SuspendingSink { sink ->
            chunksOf(payload, 700).forEach { sink.write(Buffer().also { b -> b.write(it) }) }
        }

        val out = BufferSuspendingSink()
        out.gzip().use { producer.writeSuspending(it) }
        val compressed = out.buffer.readByteArray()

        assertContentEquals(original, compressed.ungzip())
        assertTrue(compressed.size < original.size, "expected compression, got ${compressed.size} vs ${original.size}")
    }

    @Test
    fun `producer gzip honors explicit flushes without breaking the stream`() = runBlocking {
        val original = payload.toByteArray()
        val producer = Data.SuspendingSink { sink ->
            chunksOf(payload, 700).forEach {
                sink.write(Buffer().also { b -> b.write(it) })
                sink.flush()
            }
        }

        val out = BufferSuspendingSink()
        out.gzip().use { producer.writeSuspending(it) }

        assertContentEquals(original, out.buffer.readByteArray().ungzip())
    }

    @Test
    fun `producer gzip round-trips an empty stream`() = runBlocking {
        val out = BufferSuspendingSink()
        out.gzip().use { Data.SuspendingSink { }.writeSuspending(it) }

        assertContentEquals(ByteArray(0), out.buffer.readByteArray().ungzip())
    }

    @Test
    fun `producer gzip reports a failing producer downstream without appending a trailer`() = runBlocking {
        val cause = IllegalStateException("producer blew up")
        val producer = Data.SuspendingSink { sink ->
            sink.write(Buffer().also { it.write(payload.toByteArray()) })
            sink.cancel(cause)
        }

        val out = RecordingSink()
        out.gzip().use { producer.writeSuspending(it) }

        assertEquals(cause, out.cancelCause, "the abandon must be forwarded to the consumer's sink")
        assertTrue(out.closedCleanly.not(), "an abandoned stream must never be reported as complete")
        assertTrue(out.writesAfterTerminal == 0, "no trailer should follow an abandon")
    }

    private class RecordingSink : SuspendingSink {
        val buffer: Buffer = Buffer()
        var cancelCause: Throwable? = null
            private set
        var closedCleanly: Boolean = false
            private set
        var writesAfterTerminal: Int = 0
            private set

        private val terminal get() = cancelCause != null || closedCleanly

        override suspend fun write(from: Buffer) {
            if (terminal) writesAfterTerminal++
            from.transferTo(buffer)
        }

        override suspend fun flush() {}

        override suspend fun close() {
            if (!terminal) closedCleanly = true
        }

        override fun cancel(cause: Throwable) {
            if (!terminal) cancelCause = cause
        }
    }

    private fun gzipRequestHeaders() = HttpHeaders { add(HttpHeader.AcceptEncoding, "gzip") }

    @Test
    fun `interceptor compresses a cooperative source body`() {
        object : ServerBuilder() {
            init {
                registerBasicMediaTypeCoders()
                install(GzipInterceptor())
            }

            val endpoint = path.path("stream").get bind HttpHandler {
                HttpResponse(
                    body = TypedData(
                        Data.SuspendingSource(Buffer().also { it.write(payload.toByteArray()) }.asSuspendingSource()),
                        MediaType.Text.Plain
                    ),
                    headers = HttpHeaders { add(HttpHeader.ContentLength, payload.length.toString()) }
                )
            }
        }.test(settings = { generalSettings set GeneralServerSettings() }) {
            runBlocking {
                val response = endpoint.test(headers = gzipRequestHeaders())

                assertEquals("gzip", response.headers[HttpHeader.ContentEncoding]?.root)
                assertEquals(HttpHeader.AcceptEncoding, response.headers[HttpHeader.Vary]?.root)
                assertNull(response.headers[HttpHeader.ContentLength], "stale plaintext length must be dropped")
                assertContentEquals(payload.toByteArray(), response.body!!.data.bytes().ungzip())
            }
        }
    }

    @Test
    fun `interceptor compresses a cooperative producer body`() {
        object : ServerBuilder() {
            init {
                registerBasicMediaTypeCoders()
                install(GzipInterceptor())
            }

            val endpoint = path.path("produce").get bind HttpHandler {
                HttpResponse(
                    body = TypedData(
                        Data.SuspendingSink { sink ->
                            payload.toByteArray().toList().chunked(700).forEach {
                                sink.write(Buffer().also { b -> b.write(it.toByteArray()) })
                            }
                        },
                        MediaType.Text.Plain
                    )
                )
            }
        }.test(settings = { generalSettings set GeneralServerSettings() }) {
            runBlocking {
                val response = endpoint.test(headers = gzipRequestHeaders())

                assertEquals("gzip", response.headers[HttpHeader.ContentEncoding]?.root)
                assertContentEquals(payload.toByteArray(), response.body!!.data.bytes().ungzip())
            }
        }
    }

    @Test
    fun `interceptor leaves an already-encoded body alone`() {
        object : ServerBuilder() {
            init {
                registerBasicMediaTypeCoders()
                install(GzipInterceptor())
            }

            val endpoint = path.path("preencoded").get bind HttpHandler {
                HttpResponse(
                    body = TypedData(Data.Bytes(payload.toByteArray()), MediaType.Text.Plain),
                    headers = HttpHeaders { add(HttpHeader.ContentEncoding, "br") }
                )
            }
        }.test(settings = { generalSettings set GeneralServerSettings() }) {
            runBlocking {
                val response = endpoint.test(headers = gzipRequestHeaders())

                assertEquals("br", response.headers[HttpHeader.ContentEncoding]?.root)
                assertContentEquals(payload.toByteArray(), response.body!!.data.bytes())
            }
        }
    }

    @Test
    fun `interceptor passes through when the client does not accept gzip`() {
        object : ServerBuilder() {
            init {
                registerBasicMediaTypeCoders()
                install(GzipInterceptor())
            }

            val endpoint = path.path("plain").get bind HttpHandler {
                HttpResponse(body = TypedData(Data.Bytes(payload.toByteArray()), MediaType.Text.Plain))
            }
        }.test(settings = { generalSettings set GeneralServerSettings() }) {
            runBlocking {
                val response = endpoint.test()

                assertNull(response.headers[HttpHeader.ContentEncoding])
                assertContentEquals(payload.toByteArray(), response.body!!.data.bytes())
            }
        }
    }
}
