package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.engine.local.BodyTooLargeException
import com.lightningkite.services.data.readRemaining
import com.lightningkite.services.data.request
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for the request-body source itself, in isolation from a socket. The end-to-end shapes live in
 * [KtorRequestBodyStreamingTest]; these pin the per-call contract with
 * [com.lightningkite.services.data.AbstractSuspendingSource] that end-to-end tests cannot observe.
 *
 * Each test reads on the test coroutine and feeds the channel from a child job, so a failure surfaces where it is
 * asserted instead of tearing down the scope from a sibling.
 */
class KtorChannelSuspendingSourceTest {

    private fun bytes(n: Int, fill: Byte = 'x'.code.toByte()) = ByteArray(n) { fill }

    /**
     * A body that failed must never come back as a clean `-1` on a later read — that is the truncation-looks-complete
     * failure (A1) restated at the source's own contract, and it replaces the old `state` inspection.
     */
    private suspend fun assertNeverReportsACleanEnd(source: KtorChannelSuspendingSource) {
        assertFailsWith<IllegalStateException> { source.read(Buffer()) }
    }

    /** Feeds [channel] once the reader suspends; [close] false leaves the body hanging open. */
    private fun CoroutineScope.feed(channel: ByteChannel, close: Boolean = true, write: suspend () -> Unit) =
        launch {
            write()
            if (close) channel.flushAndClose()
        }

    private suspend fun ByteChannel.send(data: ByteArray) {
        writeFully(data)
        flush()
    }

    /** Content-Length body delivered in several flushes: every byte arrives, exactly once, in order. */
    @Test
    fun segmented_body_is_delivered_whole(): Unit = runBlocking {
        val channel = ByteChannel()
        val source = KtorChannelSuspendingSource(channel, maxBody = 1 shl 20, expectedLength = 3000L)
        feed(channel) { repeat(3) { i -> channel.send(bytes(1000, ('a' + i).code.toByte())) } }

        val received = source.readRemaining().readByteArray()
        assertEquals("a".repeat(1000) + "b".repeat(1000) + "c".repeat(1000), received.decodeToString())
        assertEquals(-1L, source.read(Buffer()), "a fully-read body is a clean end")
    }

    /**
     * A single read hands over only what the channel has buffered right now, so a caller that needs a fixed amount
     * uses `request`, which keeps reading until the count is available.
     */
    @Test
    fun request_spanning_many_channel_flushes_is_satisfied(): Unit = runBlocking {
        val total = 300 * 1024
        val channel = ByteChannel()
        val source = KtorChannelSuspendingSource(channel, maxBody = 1L shl 30, expectedLength = total.toLong())
        // Chunks smaller than the request, so several fill() rounds are required to satisfy one request().
        feed(channel) { repeat(10) { channel.send(bytes(30 * 1024)) } }

        val into = Buffer()
        assertTrue(source.request(into, total.toLong()), "request() should report success once the count is buffered")
        assertEquals(total.toLong(), into.size)
    }

    /** A body that stops short of its declared Content-Length is an error, never a short-but-clean body. */
    @Test
    fun content_length_underrun_is_rejected(): Unit = runBlocking {
        val channel = ByteChannel()
        val source = KtorChannelSuspendingSource(channel, maxBody = 1 shl 20, expectedLength = 20_000L)
        // Clean close, but 12k bytes short of what was promised.
        feed(channel) { channel.send(bytes(8_000)) }

        val failure = assertFailsWith<BadRequestException> { source.readRemaining() }
        assertEquals("truncated-body", failure.detail)
        assertNeverReportsACleanEnd(source)
    }

    /** An abort mid-body surfaces the channel's cause rather than a clean EOF. */
    @Test
    fun aborted_channel_surfaces_its_cause(): Unit = runBlocking {
        val channel = ByteChannel()
        val source = KtorChannelSuspendingSource(channel, maxBody = 1 shl 20, expectedLength = null)
        feed(channel, close = false) {
            channel.send(bytes(100))
            channel.cancel(IllegalStateException("connection reset"))
        }

        // Ktor wraps the abort in a ClosedByteChannelException, so match on the chain rather than the top type.
        val failure = assertFailsWith<Throwable> { source.readRemaining() }
        assertTrue(
            generateSequence(failure) { it.cause }.any { it.message == "connection reset" },
            "expected the abort cause to survive, got: $failure",
        )
        assertNeverReportsACleanEnd(source)
    }

    /**
     * Regression: `awaitContent() == false` means "closed and drained", not "closed cleanly". A chunked body has no
     * Content-Length to fall back on, so if the abort cause is not consulted directly the truncation is invisible.
     */
    @Test
    fun abort_reported_only_via_closedCause_is_not_treated_as_clean_eof(): Unit = runBlocking {
        val channel = AbortedAfterDrainChannel(bytes(100), IllegalStateException("chunked body truncated"))
        // expectedLength = null models a chunked request: nothing else can catch the truncation.
        val source = KtorChannelSuspendingSource(channel, maxBody = 1 shl 20, expectedLength = null)

        assertFailsWith<IllegalStateException> { source.readRemaining() }
        assertNeverReportsACleanEnd(source)
    }

    /** The cap is inclusive: exactly maxBody passes. */
    @Test
    fun body_at_the_cap_is_accepted(): Unit = runBlocking {
        val channel = ByteChannel()
        val source = KtorChannelSuspendingSource(channel, maxBody = 1024, expectedLength = 1024L)
        feed(channel) { channel.send(bytes(1024)) }

        assertEquals(1024, source.readRemaining().readByteArray().size)
        assertEquals(-1L, source.read(Buffer()))
    }

    /** One byte past the cap is rejected mid-stream, without buffering the whole body first. */
    @Test
    fun body_one_byte_over_the_cap_is_rejected(): Unit = runBlocking {
        val channel = ByteChannel()
        val source = KtorChannelSuspendingSource(channel, maxBody = 1024, expectedLength = 1025L)
        feed(channel) { channel.send(bytes(1025)) }

        assertFailsWith<BodyTooLargeException> { source.readRemaining() }
        assertNeverReportsACleanEnd(source)
    }

    /** Abandoning the body releases the channel so the engine is not left feeding a reader that went away. */
    @Test
    fun cancel_releases_the_channel(): Unit = runBlocking {
        val channel = ByteChannel()
        val source = KtorChannelSuspendingSource(channel, maxBody = 1 shl 20, expectedLength = 100L)
        source.cancel()
        assertTrue(channel.isClosedForWrite, "cancelling the source should have cancelled the underlying channel")
    }

    /**
     * A channel that hands over [content], then reports plain end-of-content while exposing [cause] — the shape a
     * Ktor channel takes when the abort lands outside an `awaitContent` suspension.
     */
    @OptIn(InternalAPI::class)
    private class AbortedAfterDrainChannel(content: ByteArray, private val cause: Throwable) : ByteReadChannel {
        private val buffer = Buffer().apply { write(content) }
        override val closedCause: Throwable? get() = if (buffer.exhausted()) cause else null
        override val isClosedForRead: Boolean get() = buffer.exhausted()
        override val readBuffer: Source get() = buffer
        override suspend fun awaitContent(min: Int): Boolean = buffer.size >= min
        override fun cancel(cause: Throwable?) {
            buffer.clear()
        }
    }
}
