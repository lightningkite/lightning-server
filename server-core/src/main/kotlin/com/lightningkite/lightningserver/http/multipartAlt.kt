package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.core.ContentType
import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.http.cio.internals.*
import io.ktor.http.content.*
import io.ktor.network.util.*
import io.ktor.util.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.jvm.javaio.*
import io.ktor.utils.io.streams.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import java.io.*
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Parse a multipart preamble
 * @return number of bytes copied
 */
private val dashDash = ByteBuffer.wrap("--".toByteArray())
private suspend fun parsePreambleImpl(
    input: ByteReadChannel,
    output: BytePacketBuilder,
    limit: Long = Long.MAX_VALUE
): ByteBuffer {
    val buffer = DefaultByteBufferPool.borrow()
    var copied = 0L

    try {
        while (true) {
            buffer.clear()
            val rc = input.readUntilDelimiter(CrLf, buffer)
            buffer.flip()
            if (input.isClosedForRead) throw IOException("eh?")
            if (buffer.startsWith(dashDash)) {
                // we found the delimiter!
                return ByteBuffer.wrap("\r\n".toByteArray() + buffer.moveToByteArray())
            } else {
                output.writeFully(buffer)
                output.writeText("\r\n")
                input.skipDelimiter(CrLf)
                copied += rc
                if (copied > limit) {
                    throw IOException("Multipart preamble limit of $limit bytes exceeded")
                }
            }
        }
    } finally {
        DefaultByteBufferPool.recycle(buffer)
    }
}

/**
 * Parse multipart part body copying them to [output] channel but up to [limit] bytes
 */
private suspend fun parsePartBodyImpl(
    boundaryPrefixed: ByteBuffer,
    input: ByteReadChannel,
    output: ByteWriteChannel,
    headers: HttpHeadersMap,
    limit: Long = Long.MAX_VALUE
): Long {
    val cl = headers["Content-Length"]?.parseDecLong()
    val size = if (cl != null) {
        if (cl > limit) throw IOException("Multipart part content length limit of $limit exceeded (actual size is $cl)")
        input.copyTo(output, cl)
    } else {
        copyUntilBoundary("part", boundaryPrefixed, input, { output.writeFully(it) }, limit)
    }
    output.flush()

    return size
}

/**
 * Skip multipart boundary
 * @return `true` if end channel encountered
 */
private suspend fun skipBoundary(boundaryPrefixed: ByteBuffer, input: ByteReadChannel): Boolean {
    if (!input.skipDelimiterOrEof(boundaryPrefixed)) {
        return true
    }

    var result = false
    @Suppress("DEPRECATION")
    input.lookAheadSuspend {
        awaitAtLeast(1)
        val buffer = request(0, 1)
            ?: throw IOException("Failed to pass multipart boundary: unexpected end of stream")

        if (buffer[buffer.position()] != PrefixChar) return@lookAheadSuspend
        if (buffer.remaining() > 1 && buffer[buffer.position() + 1] == PrefixChar) {
            result = true
            consumed(2)
            return@lookAheadSuspend
        }

        awaitAtLeast(2)
        val attempt2buffer = request(1, 1)
            ?: throw IOException("Failed to pass multipart boundary: unexpected end of stream")

        if (attempt2buffer[attempt2buffer.position()] == PrefixChar) {
            result = true
            consumed(2)
            return@lookAheadSuspend
        }
    }

    return result
}

private val CrLf = ByteBuffer.wrap("\r\n".toByteArray())!!
private val BoundaryTrailingBuffer = ByteBuffer.allocate(8192)!!

/**
 * Starts a multipart parser coroutine producing multipart events
 */
@OptIn(ExperimentalCoroutinesApi::class)
public fun CoroutineScope.parseMultipart(
    input: ByteReadChannel
): ReceiveChannel<MultipartEvent> = produce {
    @Suppress("DEPRECATION")

    val preamble = BytePacketBuilder()
    val boundary = parsePreambleImpl(input, preamble, 8192)

    if (preamble.size > 0) {
        send(MultipartEvent.Preamble(preamble.build()))
    }

    val trailingBuffer = BoundaryTrailingBuffer.duplicate()

    do {
        input.readUntilDelimiter(CrLf, trailingBuffer)
        if (input.readUntilDelimiter(CrLf, trailingBuffer) != 0) {
            throw IOException("Failed to parse multipart: boundary line is too long")
        }
        input.skipDelimiter(CrLf)

        val body = ByteChannel()
        val headers = CompletableDeferred<HttpHeadersMap>()
        val part = MultipartEvent.MultipartPart(headers, body)
        send(part)

        var hh: HttpHeadersMap? = null
        try {
            hh = parseHeaders(input)
            if (!headers.complete(hh)) {
                hh.release()
                throw kotlin.coroutines.cancellation.CancellationException("Multipart processing has been cancelled")
            }
            parsePartBodyImpl(boundary, input, body, hh)
        } catch (t: Throwable) {
            if (headers.completeExceptionally(t)) {
                hh?.release()
            }
            body.close(t)
            throw t
        }

        body.close()
    } while (!skipBoundary(boundary, input))

    if (input.availableForRead != 0) {
        input.skipDelimiter(CrLf)
    }

    val epilogueContent = input.readRemaining()
    if (epilogueContent.isNotEmpty) {
        send(MultipartEvent.Epilogue(epilogueContent))
    }
}

/**
 * @return number of copied bytes or 0 if a boundary of EOF encountered
 */
private suspend fun copyUntilBoundary(
    name: String,
    boundaryPrefixed: ByteBuffer,
    input: ByteReadChannel,
    writeFully: suspend (ByteBuffer) -> Unit,
    limit: Long = Long.MAX_VALUE
): Long {
    val buffer = DefaultByteBufferPool.borrow()
    var copied = 0L

    try {
        while (true) {
            buffer.clear()
            val rc = input.readUntilDelimiter(boundaryPrefixed, buffer)
            if (rc <= 0) break // got boundary or eof
            buffer.flip()
            writeFully(buffer)
            copied += rc
            if (copied > limit) {
                throw IOException("Multipart $name limit of $limit bytes exceeded")
            }
        }

        return copied
    } finally {
        DefaultByteBufferPool.recycle(buffer)
    }
}

private const val PrefixChar = '-'.code.toByte()

/**
 * Tries to skip the specified [delimiter] or fails if encounters bytes differs from the required.
 * @return `true` if the delimiter was found and skipped or `false` when EOF.
 */
@Suppress("DEPRECATION")
internal suspend fun ByteReadChannel.skipDelimiterOrEof(delimiter: ByteBuffer): Boolean {
    require(delimiter.hasRemaining())
    require(delimiter.remaining() <= DEFAULT_BUFFER_SIZE) {
        "Delimiter of ${delimiter.remaining()} bytes is too long: at most $DEFAULT_BUFFER_SIZE bytes could be checked"
    }

    var found = false

    lookAhead {
        found = tryEnsureDelimiter(delimiter) == delimiter.remaining()
    }

    if (found) {
        return true
    }

    return trySkipDelimiterSuspend(delimiter)
}

@Suppress("DEPRECATION")
private suspend fun ByteReadChannel.trySkipDelimiterSuspend(delimiter: ByteBuffer): Boolean {
    var result = true

    lookAheadSuspend {
        if (!awaitAtLeast(delimiter.remaining()) && !awaitAtLeast(1)) {
            result = false
            return@lookAheadSuspend
        }
        if (tryEnsureDelimiter(delimiter) != delimiter.remaining()) throw IOException("Broken delimiter occurred")
    }

    return result
}

@Suppress("DEPRECATION")
private fun LookAheadSession.tryEnsureDelimiter(delimiter: ByteBuffer): Int {
    val found = startsWithDelimiter(delimiter)
    if (found == -1) throw IOException("Failed to skip delimiter: actual bytes differ from delimiter bytes")
    if (found < delimiter.remaining()) return found

    consumed(delimiter.remaining())
    return delimiter.remaining()
}

@Suppress("LoopToCallChain")
private fun ByteBuffer.startsWith(prefix: ByteBuffer, prefixSkip: Int = 0): Boolean {
    val size = minOf(remaining(), prefix.remaining() - prefixSkip)
    if (size <= 0) return false

    val position = position()
    val prefixPosition = prefix.position() + prefixSkip

    for (i in 0 until size) {
        if (get(position + i) != prefix.get(prefixPosition + i)) return false
    }

    return true
}

/**
 * @return Number of bytes of the delimiter found (possibly 0 if no bytes available yet) or -1 if it doesn't start
 */
@Suppress("DEPRECATION")
private fun LookAheadSession.startsWithDelimiter(delimiter: ByteBuffer): Int {
    val buffer = request(0, 1) ?: return 0
    val index = buffer.indexOfPartial(delimiter)
    if (index != 0) return -1

    val found = minOf(buffer.remaining() - index, delimiter.remaining())
    val notKnown = delimiter.remaining() - found

    if (notKnown > 0) {
        val next = request(index + found, notKnown) ?: return found
        if (!next.startsWith(delimiter, found)) return -1
    }

    return delimiter.remaining()
}

@Suppress("LoopToCallChain")
private fun ByteBuffer.indexOfPartial(sub: ByteBuffer): Int {
    val subPosition = sub.position()
    val subSize = sub.remaining()
    val first = sub[subPosition]
    val limit = limit()

    outer@ for (idx in position() until limit) {
        if (get(idx) == first) {
            for (j in 1 until subSize) {
                if (idx + j == limit) break
                if (get(idx + j) != sub.get(subPosition + j)) continue@outer
            }
            return idx - position()
        }
    }

    return -1
}

public class CIOMultipartDataBase2(
    override val coroutineContext: CoroutineContext,
    channel: ByteReadChannel,
    private val formFieldLimit: Int = 65536,
    private val inMemoryFileUploadLimit: Int = formFieldLimit
) : MultiPartData, CoroutineScope {
    private val events: ReceiveChannel<MultipartEvent> = parseMultipart(channel)

    override suspend fun readPart(): PartData? {
        while (true) {
            val event = events.tryReceive().getOrNull() ?: break
            eventToData(event)?.let { return it }
        }

        return readPartSuspend()
    }

    private suspend fun readPartSuspend(): PartData? {
        try {
            while (true) {
                val event = events.receive()
                eventToData(event)?.let { return it }
            }
        } catch (t: ClosedReceiveChannelException) {
            return null
        }
    }

    private suspend fun eventToData(evt: MultipartEvent): PartData? {
        return try {
            when (evt) {
                is MultipartEvent.MultipartPart -> partToData(evt)
                else -> {
                    evt.release()
                    null
                }
            }
        } catch (t: Throwable) {
            evt.release()
            throw t
        }
    }

    private suspend fun partToData(part: MultipartEvent.MultipartPart): PartData {
        val headers = part.headers.await()

        val contentDisposition = headers["Content-Disposition"]?.let { ContentDisposition.parse(it.toString()) }
        val filename = contentDisposition?.parameter("filename")

        if (filename == null) {
            val packet = part.body.readRemaining(formFieldLimit.toLong()) // TODO fail if limit exceeded

            try {
                return PartData.FormItem(packet.readText(), { part.release() }, CIOHeaders(headers))
            } finally {
                packet.release()
            }
        }

        // file upload
        val buffer = ByteBuffer.allocate(inMemoryFileUploadLimit)
        part.body.readAvailable(buffer)

        val completeRead = if (buffer.remaining() > 0) {
            part.body.readAvailable(buffer) == -1
        } else false

        buffer.flip()

        if (completeRead) {
            val input = ByteArrayInputStream(buffer.array(), buffer.arrayOffset(), buffer.remaining()).asInput()
            return PartData.FileItem({ input }, { part.release() }, CIOHeaders(headers))
        }

        @Suppress("BlockingMethodInNonBlockingContext")
        val tmp = File.createTempFile("file-upload", ".tmp")

        FileOutputStream(tmp).use { stream ->
            stream.channel.use { out ->
                out.truncate(0L)

                while (true) {
                    while (buffer.hasRemaining()) {
                        out.write(buffer)
                    }
                    buffer.clear()

                    if (part.body.readAvailable(buffer) == -1) break
                    buffer.flip()
                }
            }
        }

        var closed = false
        val lazyInput = lazy {
            if (closed) throw IllegalStateException("Already disposed")
            FileInputStream(tmp).asInput()
        }

        return PartData.FileItem(
            { lazyInput.value },
            {
                closed = true
                if (lazyInput.isInitialized()) lazyInput.value.close()
                part.release()
                tmp.delete()
            },
            CIOHeaders(headers)
        )
    }
}

suspend fun InputStream.toMultipartContent(type: com.lightningkite.lightningserver.core.ContentType): HttpContent.Multipart {
    return CIOMultipartDataBase2(coroutineContext, this@toMultipartContent.toByteReadChannel(coroutineContext)).adapt(
        type
    )
}

private fun io.ktor.http.ContentType.adapt(): ContentType =
    ContentType(
        type = contentType,
        subtype = contentSubtype,
        parameters = this.parameters.associate { it.name to it.value })

private fun ContentType.adapt(): io.ktor.http.ContentType =
    ContentType(
        contentType = type,
        contentSubtype = subtype,
        parameters = parameters.map { HeaderValueParam(it.key, it.value) })

internal fun Headers.adapt(): HttpHeaders = HttpHeaders(flattenEntries())

internal fun MultiPartData.adapt(myType: com.lightningkite.lightningserver.core.ContentType): HttpContent.Multipart {
    return HttpContent.Multipart(object : Flow<HttpContent.Multipart.Part> {
        override suspend fun collect(collector: FlowCollector<HttpContent.Multipart.Part>) {
            this@adapt.forEachPart {
                collector.emit(
                    when (it) {
                        is PartData.FormItem -> HttpContent.Multipart.Part.FormItem(
                            it.name ?: "",
                            it.value
                        )

                        is PartData.FileItem -> {
                            val h = it.headers.adapt()
                            HttpContent.Multipart.Part.DataItem(
                                key = it.name ?: "",
                                filename = it.originalFileName ?: "",
                                headers = h,
                                content = HttpContent.Stream(
                                    it.streamProvider,
                                    h.contentLength,
                                    it.contentType?.adapt() ?: ContentType.Application.OctetStream
                                )
                            )
                        }

                        is PartData.BinaryItem -> {
                            val h = it.headers.adapt()
                            HttpContent.Multipart.Part.DataItem(
                                key = it.name ?: "",
                                filename = "",
                                headers = h,
                                content = HttpContent.Stream(
                                    { it.provider().asStream() },
                                    h.contentLength,
                                    it.contentType?.adapt() ?: ContentType.Application.OctetStream
                                )
                            )
                        }

                        is PartData.BinaryChannelItem -> TODO()
                    }
                )
            }
        }
    }, myType)
}

