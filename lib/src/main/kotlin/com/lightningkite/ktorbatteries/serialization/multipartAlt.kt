/*
 * Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package com.lightningkite.ktorbatteries.serialization

import io.ktor.http.*
import io.ktor.http.cio.*
import io.ktor.http.cio.internals.*
import io.ktor.network.util.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.pool.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import java.io.*
import java.io.EOFException
import java.nio.*
import kotlin.math.min


/**
 * Parse a multipart preamble
 * @return number of bytes copied
 */
private suspend fun parsePreambleImpl(
    boundaryPrefixed: ByteBuffer,
    input: ByteReadChannel,
    output: BytePacketBuilder,
    limit: Long = Long.MAX_VALUE
): Long {
    return copyUntilBoundary(
        "preamble/prologue",
        boundaryPrefixed,
        input,
        { output.writeFully(it) },
        limit
    )
}
/**
 * Parse multipart part headers
 */
private suspend fun parsePartHeadersImpl(input: ByteReadChannel): HttpHeadersMap {
    val builder = CharArrayBuilder()

    try {
        return parseHeaders(input, builder)
            ?: throw EOFException("Failed to parse multipart headers: unexpected end of stream")
    } catch (t: Throwable) {
        builder.release()
        throw t
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
public fun CoroutineScope.parseMultipart(
    boundaryPrefixed: ByteBuffer,
    input: ByteReadChannel,
    totalLength: Long?
): ReceiveChannel<MultipartEvent> = produce {
    @Suppress("DEPRECATION")
    val readBeforeParse = input.totalBytesRead
    val firstBoundary = boundaryPrefixed.duplicate()!!.apply {
        position(2)
    }

    val preamble = BytePacketBuilder()
    parsePreambleImpl(firstBoundary, input, preamble, 8192)

    if (preamble.size > 0) {
        send(MultipartEvent.Preamble(preamble.build()))
    }

    if (skipBoundary(firstBoundary, input)) {
        return@produce
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
            hh = parsePartHeadersImpl(input)
            if (!headers.complete(hh)) {
                hh.release()
                throw kotlin.coroutines.cancellation.CancellationException("Multipart processing has been cancelled")
            }
            parsePartBodyImpl(boundaryPrefixed, input, body, hh)
        } catch (t: Throwable) {
            if (headers.completeExceptionally(t)) {
                hh?.release()
            }
            body.close(t)
            throw t
        }

        body.close()
    } while (!skipBoundary(boundaryPrefixed, input))

    if (input.availableForRead != 0) {
        input.skipDelimiter(CrLf)
    }

    if (totalLength != null) {
        @Suppress("DEPRECATION")
        val consumedExceptEpilogue = input.totalBytesRead - readBeforeParse
        val size = totalLength - consumedExceptEpilogue
        if (size > Int.MAX_VALUE) throw IOException("Failed to parse multipart: prologue is too long")
        if (size > 0) {
            send(MultipartEvent.Epilogue(input.readPacket(size.toInt())))
        }
    } else {
        val epilogueContent = input.readRemaining()
        if (epilogueContent.isNotEmpty) {
            send(MultipartEvent.Epilogue(epilogueContent))
        }
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

private fun findBoundary(contentType: CharSequence): Int {
    var state = 0 // 0 header value, 1 param name, 2 param value unquoted, 3 param value quoted, 4 escaped
    var paramNameCount = 0

    for (i in contentType.indices) {
        val ch = contentType[i]

        when (state) {
            0 -> {
                if (ch == ';') {
                    state = 1
                    paramNameCount = 0
                }
            }
            1 -> {
                if (ch == '=') {
                    state = 2
                } else if (ch == ';') {
                    // do nothing
                    paramNameCount = 0
                } else if (ch == ',') {
                    state = 0
                } else if (ch == ' ') {
                    // do nothing
                } else if (paramNameCount == 0 && contentType.startsWith("boundary=", i, ignoreCase = true)) {
                    return i
                } else {
                    paramNameCount++
                }
            }
            2 -> {
                when (ch) {
                    '"' -> state = 3
                    ',' -> state = 0
                    ';' -> {
                        state = 1
                        paramNameCount = 0
                    }
                }
            }
            3 -> {
                if (ch == '"') {
                    state = 1
                    paramNameCount = 0
                } else if (ch == '\\') {
                    state = 4
                }
            }
            4 -> {
                state = 3
            }
        }
    }

    return -1
}

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


internal class CharArrayBuilder(
    val pool: ObjectPool<CharArray> = CharArrayPool
) : CharSequence, Appendable {

    private var buffers: MutableList<CharArray>? = null
    private var current: CharArray? = null
    private var stringified: String? = null
    private var released: Boolean = false
    private var remaining: Int = 0

    override var length: Int = 0
        private set

    override fun get(index: Int): Char {
        require(index >= 0) { "index is negative: $index" }
        require(index < length) { "index $index is not in range [0, $length)" }

        return getImpl(index)
    }

    private fun getImpl(index: Int) = bufferForIndex(index).get(index % current!!.size)

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        require(startIndex <= endIndex) { "startIndex ($startIndex) should be less or equal to endIndex ($endIndex)" }
        require(startIndex >= 0) { "startIndex is negative: $startIndex" }
        require(endIndex <= length) { "endIndex ($endIndex) is greater than length ($length)" }

        return SubSequenceImpl(startIndex, endIndex)
    }

    override fun toString(): String = stringified ?: copy(0, length).toString().also { stringified = it }

    override fun equals(other: Any?): Boolean {
        if (other !is CharSequence) return false
        if (length != other.length) return false

        return rangeEqualsImpl(0, other, 0, length)
    }

    override fun hashCode(): Int = stringified?.hashCode() ?: hashCodeImpl(0, length)

    override fun append(value: Char): Appendable {
        nonFullBuffer()[current!!.size - remaining] = value
        stringified = null
        remaining -= 1
        length++
        return this
    }

    override fun append(value: CharSequence?, startIndex: Int, endIndex: Int): Appendable {
        value ?: return this

        var current = startIndex
        while (current < endIndex) {
            val buffer = nonFullBuffer()
            val offset = buffer.size - remaining
            val bytesToCopy = min(endIndex - current, remaining)

            for (i in 0 until bytesToCopy) {
                buffer[offset + i] = value[current + i]
            }

            current += bytesToCopy
            remaining -= bytesToCopy
        }

        stringified = null
        length += endIndex - startIndex
        return this
    }

    override fun append(value: CharSequence?): Appendable {
        value ?: return this
        return append(value, 0, value.length)
    }

    fun release() {
        val list = buffers

        if (list != null) {
            current = null
            for (i in 0 until list.size) {
                pool.recycle(list[i])
            }
        } else {
            current?.let { pool.recycle(it) }
            current = null
        }

        released = true
        buffers = null
        stringified = null
        length = 0
        remaining = 0
    }

    private fun copy(startIndex: Int, endIndex: Int): CharSequence {
        if (startIndex == endIndex) return ""

        val builder = StringBuilder(endIndex - startIndex)

        var buffer: CharArray

        var base = startIndex - (startIndex % CHAR_BUFFER_ARRAY_LENGTH)

        while (base < endIndex) {
            buffer = bufferForIndex(base)
            val innerStartIndex = maxOf(0, startIndex - base)
            val innerEndIndex = minOf(endIndex - base, CHAR_BUFFER_ARRAY_LENGTH)

            for (innerIndex in innerStartIndex until innerEndIndex) {
                builder.append(buffer.get(innerIndex))
            }

            base += CHAR_BUFFER_ARRAY_LENGTH
        }

        return builder
    }

    @Suppress("ConvertTwoComparisonsToRangeCheck")
    private inner class SubSequenceImpl(val start: Int, val end: Int) : CharSequence {
        private var stringified: String? = null

        override val length: Int
            get() = end - start

        override fun get(index: Int): Char {
            val withOffset = index + start
            require(index >= 0) { "index is negative: $index" }
            require(withOffset < end) { "index ($index) should be less than length ($length)" }

            return this@CharArrayBuilder.getImpl(withOffset)
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            require(startIndex >= 0) { "start is negative: $startIndex" }
            require(startIndex <= endIndex) { "start ($startIndex) should be less or equal to end ($endIndex)" }
            require(endIndex <= end - start) { "end should be less than length ($length)" }
            if (startIndex == endIndex) return ""

            return SubSequenceImpl(start + startIndex, start + endIndex)
        }

        override fun toString() = stringified ?: copy(start, end).toString().also { stringified = it }

        override fun equals(other: Any?): Boolean {
            if (other !is CharSequence) return false
            if (other.length != length) return false

            return rangeEqualsImpl(start, other, 0, length)
        }

        override fun hashCode() = stringified?.hashCode() ?: hashCodeImpl(start, end)
    }

    private fun bufferForIndex(index: Int): CharArray {
        val list = buffers

        if (list == null) {
            if (index >= CHAR_BUFFER_ARRAY_LENGTH) throwSingleBuffer(index)
            return current ?: throwSingleBuffer(index)
        }

        return list[index / current!!.size]
    }

    private fun throwSingleBuffer(index: Int): Nothing {
        if (released) throw IllegalStateException("Buffer is already released")
        throw IndexOutOfBoundsException("$index is not in range [0; ${currentPosition()})")
    }

    private fun nonFullBuffer(): CharArray {
        return if (remaining == 0) appendNewArray() else current!!
    }

    private fun appendNewArray(): CharArray {
        val newBuffer = pool.borrow()
        val existing = current
        current = newBuffer
        remaining = newBuffer.size

        released = false

        if (existing != null) {
            val list = buffers ?: ArrayList<CharArray>().also {
                buffers = it
                it.add(existing)
            }

            list.add(newBuffer)
        }

        return newBuffer
    }

    private fun rangeEqualsImpl(start: Int, other: CharSequence, otherStart: Int, length: Int): Boolean {
        for (i in 0 until length) {
            if (getImpl(start + i) != other[otherStart + i]) return false
        }

        return true
    }

    private fun hashCodeImpl(start: Int, end: Int): Int {
        var hc = 0
        for (i in start until end) {
            hc = 31 * hc + getImpl(i).code
        }

        return hc
    }

    private fun currentPosition() = current!!.size - remaining
}

/**
 * Parse HTTP headers. Not applicable to request and response status lines.
 */
internal suspend fun parseHeaders(
    input: ByteReadChannel,
    builder: io.ktor.http.cio.internals.CharArrayBuilder,
    range: MutableRange = MutableRange(0, 0)
): HttpHeadersMap? {
    val headers = HttpHeadersMap(builder)

    try {
        while (true) {
            if (!input.readUTF8LineTo(builder, HTTP_LINE_LIMIT)) {
                headers.release()
                return null
            }

            range.end = builder.length
            val rangeLength = range.end - range.start

            if (rangeLength == 0) break
            if (rangeLength >= HTTP_LINE_LIMIT) error("Header line length limit exceeded")

            val nameStart = range.start
            val nameEnd = parseHeaderName(builder, range)

            val nameHash = builder.hashCodeLowerCase(nameStart, nameEnd)

            val headerEnd = range.end
            parseHeaderValue(builder, range)

            val valueStart = range.start
            val valueEnd = range.end
            val valueHash = builder.hashCodeLowerCase(valueStart, valueEnd)
            range.start = headerEnd

            headers.put(nameHash, valueHash, nameStart, nameEnd, valueStart, valueEnd)
        }

        val host = headers[HttpHeaders.Host]
        if (host != null && host.any { hostForbiddenSymbols.contains(it) }) {
            error("Host cannot contain any of the following symbols: $hostForbiddenSymbols")
        }

        return headers
    } catch (t: Throwable) {
        headers.release()
        throw t
    }
}