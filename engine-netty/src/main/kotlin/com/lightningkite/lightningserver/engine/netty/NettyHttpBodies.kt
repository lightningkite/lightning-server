@file:OptIn(InternalLightningServerApi::class)

package com.lightningkite.lightningserver.engine.netty

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.engine.local.BodyTooLargeException
import com.lightningkite.lightningserver.http.*
import com.lightningkite.services.data.*
import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.EventLoop
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames.*
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.buffered
import kotlinx.io.readAtMostTo
import kotlinx.io.transferFrom
import java.io.IOException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import io.netty.handler.codec.http.HttpHeaders as NettyHttpHeaders
import kotlinx.coroutines.channels.Channel as CoroutineChannel

// Request and response bodies, streamed between a connection and the handler answering it.

/** How much of a request body may sit unread in memory before the connection stops reading more. */
private const val BODY_BUFFER_BYTES = 64 * 1024

/** Throws if this write has already failed, which it does once the connection has gone. */
private fun ChannelFuture.throwIfFailed() {
    if (isDone && !isSuccess) throw IOException("The connection closed before the response was sent", cause())
}

/**
 * Moves the first [byteCount] bytes of this buffer into a new [ByteBuf] from [alloc], with one copy.
 *
 * The bytes are read straight into the new buffer's memory (pooled and, normally, direct, so the
 * socket write needs no further copy) instead of going through a temporary array.
 */
private fun Buffer.readByteBuf(alloc: ByteBufAllocator, byteCount: Int): ByteBuf {
    val out = alloc.buffer(byteCount)
    try {
        // A buffer fresh from the allocator is one contiguous region, so this view shares its memory.
        val view = out.nioBuffer(out.writerIndex(), byteCount)
        while (view.hasRemaining()) {
            if (readAtMostTo(view) <= 0) throw java.io.EOFException("The buffer held fewer than $byteCount bytes")
        }
        out.writerIndex(out.writerIndex() + byteCount)
        return out
    } catch (e: Throwable) {
        out.release()
        throw e
    }
}

/**
 * Writes [msg] and, if that leaves the channel over its high water mark, suspends until the write
 * has reached the socket.
 *
 * This is what makes the water marks mean something: without it a sender that outpaces its peer
 * just grows the channel's outbound buffer until the heap runs out.
 *
 * @param throwOnFailure whether a failed write throws. Pieces of an HTTP response pass true, so
 *   whatever is producing the body stops instead of streaming the rest of it at a connection that
 *   is gone. WebSocket sends pass false: a write fails because the socket is closing, and the
 *   close paths are what report that.
 */
internal suspend fun Channel.writeAwaitingRoom(msg: Any, throwOnFailure: Boolean) {
    val future = writeAndFlush(msg)
    if (!isWritable) future.awaitDone()
    if (throwOnFailure) future.throwIfFailed()
}

/** Suspends until this write has reached the socket or failed. */
private suspend fun ChannelFuture.awaitDone() {
    if (!isDone) suspendCancellableCoroutine { cont -> addListener { cont.resume(Unit) } }
}

/**
 * A request body, handed to the handler as it arrives.
 *
 * The event loop [offer]s chunks as the decoder produces them; the handler pulls them with
 * [read]. The connection only asks the socket for more while [wantsMore], so a handler that
 * reads slowly holds the client back through TCP instead of the upload piling up in memory.
 *
 * A body that ends early never looks complete: a dropped connection or broken framing is passed
 * to the reader as a failure by [fail], and a body that grows past [maxBody] throws
 * [BodyTooLargeException] from the read that crossed the line.
 */
internal class RequestBodySource(
    private val loop: EventLoop,
    private val maxBody: Long,
    /** Run on [loop] when the socket can be read again: the buffer has drained, or nobody will read the body. */
    private val onRoom: () -> Unit,
) : AbstractSuspendingSource() {
    // A chunk handed to a reader that is cancelled before it takes it would otherwise be lost
    // with its buffer still retained.
    private val chunks = CoroutineChannel<ByteBuf>(CoroutineChannel.UNLIMITED, onUndeliveredElement = { it.release() })
    private val buffered = AtomicLong()
    private var readSoFar = 0L

    @Volatile
    private var released = false

    /** The body went over [maxBody]. The response is a 413 whatever the handler made of that. */
    @Volatile
    var exceeded = false
        private set

    /** Once nobody will read the body, keep reading the socket so the rest can be discarded. */
    fun wantsMore(): Boolean = released || buffered.get() < BODY_BUFFER_BYTES

    /** Queues [content] for the reader. Returns false if nobody will read it, so it was dropped. */
    fun offer(content: ByteBuf): Boolean {
        if (released) return false
        buffered.addAndGet(content.readableBytes().toLong())
        // Fails only if release() closed the channel in between, in which case nobody wants it.
        if (chunks.trySend(content.retain()).isFailure) {
            content.release()
            return false
        }
        return true
    }

    fun complete() {
        chunks.close()
    }

    /** Ends the body with a failure. Does nothing to a body that has already ended. */
    fun fail(cause: Throwable) {
        chunks.close(cause)
    }

    override suspend fun fill(into: Buffer): Long {
        val received = chunks.receiveCatching()
        val chunk = received.getOrNull() ?: run {
            received.exceptionOrNull()?.let { throw IOException("The request body ended early", it) }
            return -1L
        }
        val size = chunk.readableBytes().toLong()
        try {
            // Straight from Netty's memory into the buffer, one copy, with no array in between.
            for (piece in chunk.nioBuffers()) into.transferFrom(piece)
        } finally {
            chunk.release()
        }
        readSoFar += size
        if (readSoFar > maxBody) {
            exceeded = true
            throw BodyTooLargeException()
        }
        val before = buffered.getAndAdd(-size)
        if (before >= BODY_BUFFER_BYTES && before - size < BODY_BUFFER_BYTES) signalRoom()
        return size
    }

    override fun release(cause: Throwable?) {
        released = true
        chunks.close()
        while (true) chunks.tryReceive().getOrNull()?.release() ?: break
        // If reading had stopped because the buffer was full, it can resume: what is still to come
        // is only thrown away.
        signalRoom()
    }

    private fun signalRoom() {
        try {
            loop.execute(onRoom)
        } catch (_: RejectedExecutionException) {
            // The loop is shutting down; there is no socket left to read.
        }
    }
}

/**
 * A blocking sink that writes response body chunks, for bodies produced by blocking code
 * ([Data.Sink], [Data.Source]). Only ever used from [Dispatchers.IO]: when the client is slow it
 * blocks its thread, which must never be an event loop.
 */
private class BlockingResponseSink(private val channel: Channel) : RawSink {
    /**
     * Queues the bytes without flushing: a buffered sink hands over every full segment (8 KiB),
     * and a flush for each would cost a syscall each. The channel is flushed once it reaches its
     * high water mark, and then this waits for that to drain, which is also what holds a fast
     * producer back to the client's pace.
     *
     * A single call may carry far more than a segment (a buffered sink hands over every complete
     * segment at once, so one large `write(ByteArray)` arrives here whole), so it is sent in
     * pieces of at most [BODY_BUFFER_BYTES]: a piece never needs a buffer larger than that, and
     * the wait for room happens between pieces.
     */
    override fun write(source: Buffer, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0L) {
            val piece = minOf(remaining, BODY_BUFFER_BYTES.toLong()).toInt()
            val future = channel.write(DefaultHttpContent(source.readByteBuf(channel.alloc(), piece)))
            remaining -= piece
            if (!channel.isWritable) {
                channel.flush()
                future.await()
            }
            future.throwIfFailed()
        }
    }

    override fun flush() {
        channel.flush()
    }

    override fun close() {
        channel.flush()
    }
}

/** A cooperative sink that writes response body chunks, suspending while the client is slow. */
private class ResponseSuspendingSink(private val channel: Channel) : AbstractSuspendingSink() {
    /** A flush is on its way to the event loop, and will take everything written before it runs. */
    private val flushQueued = AtomicBoolean(false)

    /**
     * Queues the bytes, and asks for one flush to cover however many writes land before the event
     * loop gets to it. A producer writing in small pieces, a row at a time, then pays for a
     * syscall per batch rather than per piece, while a piece written on its own still goes out
     * at once. Over the high water mark it flushes and waits, which holds a fast producer back to
     * the client's pace.
     */
    override suspend fun write(from: Buffer) {
        checkWritable()
        while (from.size > 0L) {
            val piece = minOf(from.size, BODY_BUFFER_BYTES.toLong()).toInt()
            val future = channel.write(DefaultHttpContent(from.readByteBuf(channel.alloc(), piece)))
            if (!channel.isWritable) {
                channel.flush()
                future.awaitDone()
            } else if (flushQueued.compareAndSet(false, true)) {
                channel.eventLoop().execute {
                    flushQueued.set(false)
                    channel.flush()
                }
            }
            future.throwIfFailed()
        }
    }

    override suspend fun flush() {
        checkWritable()
        channel.flush()
    }

    // Nothing to do here: whoever is sending the response ends it, or closes the connection if
    // the body failed part-way so a short body is never framed as a complete one.
    override fun release(cause: Throwable?) {}
}

/** What sending a response settled: the last write, and whether the connection can be reused. */
internal class Sent(val lastWrite: ChannelFuture, val reusable: Boolean)

/** Copies this response's headers, and its body's media type, onto [target]. */
private fun HttpResponse.copyHeadersTo(target: NettyHttpHeaders) {
    for ((key, values) in this.headers.normalizedEntries) {
        for (value in values) {
            target.add(key, value.toHttpString())
        }
    }
    this.body?.mediaType?.let { mt ->
        target[CONTENT_TYPE] = mt.toString()
    }
}

/** The whole response as one message, body included. Loads a streaming body into memory. */
private suspend fun HttpResponse.toNettyResponse(version: HttpVersion): FullHttpResponse {
    val contentBuf = this.body?.data?.bytes()
        ?.let { Unpooled.wrappedBuffer(it) }
        ?: Unpooled.EMPTY_BUFFER

    val res = DefaultFullHttpResponse(version, HttpResponseStatus.valueOf(this.status.code), contentBuf)
    copyHeadersTo(res.headers())
    // Always advertise the body length, including 0 for bodyless responses (redirects, etc.).
    // Without a Content-Length (or 0) a keep-alive HTTP/1.1 client cannot tell the response is
    // complete and stalls until the idle timeout closes the connection — a cross-engine
    // conformance defect caught by EngineHttpConformanceSuite.trailing_slash_redirects_307.
    res.headers()[CONTENT_LENGTH] = contentBuf.readableBytes().toString()

    return res
}

/**
 * Sends [response]. Bodies already in memory go out in one message; streaming bodies go out as
 * they are produced, with `Content-Length` when the size is known and chunked encoding otherwise.
 *
 * [onCommitted] is called once the status line is on its way. A failure after that point cannot
 * be turned into an error response; the caller has to close the connection instead.
 */
internal suspend fun sendResponse(
    channel: Channel,
    response: HttpResponse,
    version: HttpVersion,
    isHead: Boolean,
    reusable: Boolean,
    streaming: Boolean,
    onCommitted: () -> Unit,
): Sent {
    val data = response.body?.data
    if (data == null || data is Data.Bytes || data is Data.Text || !streaming) {
        val full = response.toNettyResponse(version)
        full.headers()[CONNECTION] = if (reusable) HttpHeaderValues.KEEP_ALIVE else HttpHeaderValues.CLOSE
        onCommitted()
        return Sent(channel.writeAndFlush(full), reusable)
    }

    var reuse = reusable
    try {
        val head = DefaultHttpResponse(version, HttpResponseStatus.valueOf(response.status.code))
        response.copyHeadersTo(head.headers())
        val size = data.size
        when {
            size != null -> head.headers()[CONTENT_LENGTH] = size.toString()
            head.headers().contains(CONTENT_LENGTH) -> {}
            isHead -> {}
            version.isKeepAliveDefault -> HttpUtil.setTransferEncodingChunked(head, true)
            // HTTP/1.0 has no chunked encoding: the body ends when the connection does.
            else -> reuse = false
        }
        head.headers()[CONNECTION] = if (reuse) HttpHeaderValues.KEEP_ALIVE else HttpHeaderValues.CLOSE
        onCommitted()
        channel.writeAwaitingRoom(head, throwOnFailure = true)
    } catch (e: Throwable) {
        // Whatever sends the body also lets go of it, and that has not started. A body may be
        // holding a file or a database cursor open.
        try {
            data.close()
        } catch (closing: Throwable) {
            e.addSuppressed(closing)
        }
        throw e
    }
    if (isHead) {
        data.close()
    } else when (data) {
        // Blocking producers run on the IO pool so they can never stall an event loop or a handler thread.
        is Data.Sink -> withContext(Dispatchers.IO) {
            BlockingResponseSink(channel).buffered().use { data.emit(it) }
        }

        is Data.Source -> withContext(Dispatchers.IO) {
            BlockingResponseSink(channel).buffered().use { sink -> data.source.use { sink.transferFrom(it) } }
        }

        else -> ResponseSuspendingSink(channel).use { data.writeSuspending(it) }
    }
    return Sent(channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT), reuse)
}
