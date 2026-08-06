package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.services.data.AbstractSuspendingSink
import com.lightningkite.services.data.SuspendingSink
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeBuffer
import kotlinx.io.Buffer

/**
 * A [SuspendingSink] over a Ktor [ByteWriteChannel] — the cooperative, non-blocking response-body channel.
 *
 * Writes *suspend* (yielding the event-loop thread when the channel applies backpressure) instead of blocking it, so
 * streaming a response to a slow-reading client can never pin the event loop the way bridging through a blocking
 * `kotlinx.io.Sink` would.
 *
 * Lifecycle: this does **not** close the underlying channel — `respondBytesWriter` owns and closes it when its block
 * returns. A clean close only flushes; cancelling cancels the channel, which is what makes the client see a truncated
 * response rather than a complete one over a half-written body.
 */
internal class KtorChannelSuspendingSink(private val channel: ByteWriteChannel) : AbstractSuspendingSink() {

    override suspend fun write(from: Buffer) {
        checkWritable()
        // writeBuffer consumes the bytes from `from` and suspends for backpressure (never blocks the thread).
        channel.writeBuffer(from, from.size)
    }

    override suspend fun flush() {
        checkWritable()
        channel.flush()
    }

    override suspend fun finish() {
        channel.flush()
    }

    /**
     * A null cause can only come from a clean [close], and the channel's owner completes the response for us — so
     * there is nothing to do. Any other cause means the body is short, and cancelling is the only way to stop the
     * engine from framing a truncated response as a complete one.
     */
    override fun release(cause: Throwable?) {
        cause?.let { channel.cancel(it) }
    }
}
