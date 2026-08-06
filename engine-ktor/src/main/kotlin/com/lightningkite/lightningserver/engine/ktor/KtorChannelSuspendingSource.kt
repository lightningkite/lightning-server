package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.engine.local.BodyTooLargeException
import com.lightningkite.services.data.AbstractSuspendingSource
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.io.Buffer

/**
 * A [com.lightningkite.services.data.SuspendingSource] over a Ktor [ByteReadChannel] — the cooperative, non-blocking
 * request-body channel.
 *
 * Reads *suspend* (yielding the engine's event-loop thread so it can keep delivering socket bytes) instead of blocking
 * a thread, which is what makes streamed/slow request bodies safe to consume directly on the event loop. It also
 * enforces [maxBody], throwing [BodyTooLargeException] as soon as the cumulative body exceeds it. Lifecycle bookkeeping
 * is handled by [AbstractSuspendingSource]; this only supplies the [fill] primitive.
 *
 * ## Truncated uploads never look complete (finding A1)
 *
 * A client that dies mid-upload can end the body two ways, and both must surface as an error rather than a short-but-
 * "clean" body the handler mistakes for the whole request:
 * - **Reset / broken framing** (e.g. an incomplete chunked body): the channel closes *with a cause*, which we rethrow —
 *   either from [io.ktor.utils.io.ByteReadChannel.awaitContent] or, when the abort lands outside its suspension, from
 *   [io.ktor.utils.io.ByteReadChannel.closedCause] on the EOF path.
 * - **Content-Length underrun** (socket half-closed after fewer bytes than promised): the channel reports a *clean*
 *   EOF with no cause, so we compare against [expectedLength] ourselves and reject the truncated body.
 */
internal class KtorChannelSuspendingSource(
    private val channel: ByteReadChannel,
    private val maxBody: Long,
    private val expectedLength: Long?,
) : AbstractSuspendingSource() {
    private var readSoFar = 0L

    @OptIn(InternalAPI::class)
    override suspend fun fill(into: Buffer): Long {
        // Suspend until at least one byte is buffered. Returns false once the channel is closed and drained.
        if (!channel.awaitContent()) return endOfStream()
        // Take everything currently buffered in one shot — no stalling until a fixed-size chunk fills, so trickle/
        // segmented bodies flow through with minimal latency. Unbounded is safe because awaitContent() has already
        // guaranteed buffered bytes, so this hands those over rather than pulling more from the socket.
        val moved = channel.readBuffer.readAtMostTo(into, Long.MAX_VALUE)
        // awaitContent() guaranteed a byte, so moved > 0 here; the guard is defensive and must honor the same
        // truncation check rather than reporting a silent clean EOF.
        if (moved <= 0L) return endOfStream()
        readSoFar += moved
        if (readSoFar > maxBody) throw BodyTooLargeException()
        return moved
    }

    /** Handle a channel EOF: a clean end unless the channel aborted or a declared Content-Length says bytes are still owed (A1). */
    private fun endOfStream(): Long {
        // `awaitContent() == false` only means "closed with nothing left buffered" — it does not by itself mean the
        // close was clean. Ktor rethrows the cause only when it lands while we are suspended inside awaitContent, so
        // consult it directly here too; otherwise an aborted chunked body (no Content-Length to fall back on) would
        // be reported as a clean, silently truncated request. Same idiom Ktor uses after its own awaitContent loops.
        channel.closedCause?.let { throw it }
        if (expectedLength != null && readSoFar < expectedLength) {
            throw BadRequestException(
                detail = "truncated-body",
                message = "Request body ended after $readSoFar bytes but Content-Length declared $expectedLength.",
            )
        }
        return -1L
    }

    override fun release(cause: Throwable?) {
        channel.cancel(cause)
    }
}
