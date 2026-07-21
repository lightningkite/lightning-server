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
 * enforces [maxBody], throwing [BodyTooLargeException] as soon as the cumulative body exceeds it. State/EOF bookkeeping
 * is handled by [AbstractSuspendingSource]; this only supplies the [fill] primitive.
 *
 * ## Truncated uploads never look complete (finding A1)
 *
 * A client that dies mid-upload can end the body two ways, and both must surface as an error rather than a short-but-
 * "clean" body the handler mistakes for the whole request:
 * - **Reset / broken framing** (e.g. an incomplete chunked body): the channel closes *with a cause*, which
 *   [io.ktor.utils.io.ByteReadChannel.awaitContent] rethrows.
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
    override suspend fun fill(into: Buffer, count: Long): Boolean {
        // Suspend until at least one byte is buffered. Returns false at EOF; THROWS if the client aborted with a cause.
        if (!channel.awaitContent()) return endOfStream()
        // Take everything currently buffered in one shot — no stalling until a fixed-size chunk fills, so trickle/
        // segmented bodies flow through with minimal latency.
        val moved = channel.readBuffer.readAtMostTo(into, READ_AHEAD)
        // awaitContent() guaranteed a byte, so moved > 0 here; the guard is defensive and must honor the same
        // truncation check rather than reporting a silent clean EOF.
        if (moved <= 0L) return endOfStream()
        readSoFar += moved
        if (readSoFar > maxBody) throw BodyTooLargeException()
        return true
    }

    /** Handle a channel EOF: a clean end unless a declared Content-Length says bytes are still owed (A1). */
    private fun endOfStream(): Boolean {
        if (expectedLength != null && readSoFar < expectedLength) {
            throw BadRequestException(
                detail = "truncated-body",
                message = "Request body ended after $readSoFar bytes but Content-Length declared $expectedLength.",
            )
        }
        return false
    }

    override fun release(cause: Throwable?) {
        channel.cancel(cause)
    }

    private companion object {
        // Upper bound per fill; the channel rarely has more than a segment buffered, so this just caps a single move.
        const val READ_AHEAD = 64L * 1024
    }
}
