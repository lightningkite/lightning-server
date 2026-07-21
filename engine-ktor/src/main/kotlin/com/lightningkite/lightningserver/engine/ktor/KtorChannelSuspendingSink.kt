package com.lightningkite.lightningserver.engine.ktor

import com.lightningkite.services.data.StreamState
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
 * returns. [close] only flushes; [close] with a cause cancels the channel so the client sees a truncated response.
 */
internal class KtorChannelSuspendingSink(private val channel: ByteWriteChannel) : SuspendingSink {
    override var state: StreamState = StreamState.Open
        private set

    override suspend fun write(from: Buffer, count: Long) {
        // writeBuffer consumes `count` bytes from `from` and suspends for backpressure (never blocks the thread).
        channel.writeBuffer(from, count)
    }

    override suspend fun flush() {
        channel.flush()
    }

    override suspend fun close() {
        if (state != StreamState.Open) return
        channel.flush()
        state = StreamState.Complete
    }

    override fun close(cause: Throwable) {
        if (state != StreamState.Open) return
        channel.cancel(cause)
        state = StreamState.ClosedAbnormally(cause)
    }
}
