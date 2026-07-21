package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.services.data.*
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.writeString

/** Sentinel for [RangeSlicingSink.forward] meaning "forward everything after the skip, to end of stream". */
private const val RANGE_UNTIL_END: Long = -1L

/**
 * A [SuspendingSink] that slices a passing byte stream: it discards the first [skip] bytes, forwards the next
 * [forward] bytes to [downstream] (or all remaining bytes if [forward] is [RANGE_UNTIL_END]), and discards anything
 * past that. Lets a range be served straight from a streaming body without buffering the whole thing in memory.
 *
 * Does not close [downstream] — the enclosing producer owns its lifecycle.
 */
private class RangeSlicingSink(
    private val downstream: SuspendingSink,
    skip: Long,
    private val forward: Long,
) : SuspendingSink {
    private var toSkip = skip
    private var forwarded = 0L

    override val state: StreamState get() = downstream.state

    override suspend fun write(from: Buffer, count: Long) {
        var remaining = count
        if (toSkip > 0L) {
            val dropped = minOf(toSkip, remaining)
            from.skip(dropped); toSkip -= dropped; remaining -= dropped
        }
        if (remaining <= 0L) return
        val allowed = if (forward == RANGE_UNTIL_END) remaining else minOf(remaining, forward - forwarded)
        if (allowed > 0L) {
            downstream.write(from, allowed); forwarded += allowed; remaining -= allowed
        }
        if (remaining > 0L) from.skip(remaining) // past the window — discard, but still consume `count` from `from`
    }

    override suspend fun flush(): Unit = downstream.flush()
    override suspend fun close() {} // downstream is caller-owned
    override fun close(cause: Throwable) {}
}

/**
 * Represents a single range value requested by a `Range` header.
 *
 * Valid range formats (source: [mdn](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Range)):
 *
 * ```
 * Range: <unit>=<range-start>-
 * Range: <unit>=<range-start>-<range-end>
 * Range: <unit>=<range-start>-<range-end>, …, <range-startN>-<range-endN>
 * Range: <unit>=-<suffix-length>
 * ```
 * */
public sealed interface HttpRange {
    public fun rangeStart(resourceSize: Long): Long
    public fun rangeEnd(resourceSize: Long): Long

    /**
     * A range of the form `<range-start>-<range-end>`. Range starts at [rangeStart] and ends at [rangeEnd], end inclusive.
     * */
    public data class Bounded(val rangeStart: Long, val rangeEnd: Long) : HttpRange {
        // RFC 9110: both ends are inclusive, so `bytes=0-0` is one byte and `bytes=100-109` is ten. This is the byte
        // count the body must carry to match the `Content-Range: bytes start-end/total` header the endpoint sends.
        val size: Long get() = rangeEnd - rangeStart + 1

        override fun rangeStart(resourceSize: Long): Long = rangeStart
        override fun rangeEnd(resourceSize: Long): Long = rangeEnd
    }

    /**
     * A range of the form `<range-start>-`. Range starts at [rangeStart] and ends at the end of the resource.
     * */
    public data class UntilEnd(val rangeStart: Long) : HttpRange {
        override fun rangeStart(resourceSize: Long): Long = rangeStart
        override fun rangeEnd(resourceSize: Long): Long = resourceSize - 1
    }

    /**
     * A range of the form `-<suffix-length>`. Retrieves the last [suffixLength] units of the resource.
     * */
    public data class Last(val suffixLength: Long) : HttpRange {
        override fun rangeStart(resourceSize: Long): Long = resourceSize - suffixLength
        override fun rangeEnd(resourceSize: Long): Long = resourceSize - 1
    }

    public enum class MalformedBehavior { IgnoreRangeRequest, BadRequest }
}

public fun HttpHeaders.httpRanges(
    malformedRanges: HttpRange.MalformedBehavior = HttpRange.MalformedBehavior.IgnoreRangeRequest,
): List<HttpRange>? = this
    .getMany(HttpHeader.Range)
    .takeUnless { it.isEmpty() }
    ?.mapIndexed { idx, value ->
        val rStr = if (idx == 0) {  // range header is submitted in format <unit>=<range> (,<range>)*
            val unit = value.root.substringBefore('=').trim()
            if (!unit.equals(
                    "bytes",
                    ignoreCase = true
                )
            ) return null // RFC 9110 14.2: An origin server MUST ignore a Range header field that contains a range unit it does not understand.
            value.root.substringAfter('=')
        } else value.root

        rStr.split('-').let parse@{ split ->    // <range> format: <start?>-<end?> (ex. 5-10, -10, 5-)
            if (split.size != 2) return@parse null
            val start = split[0].let {
                if (it.isBlank()) null
                else it.toLongOrNull() ?: return@parse null
            }
            val end = split[1].let {
                if (it.isBlank()) null
                else it.toLongOrNull() ?: return@parse null
            }
            when {
                start == null && end == null -> null
                start == null -> HttpRange.Last(end!!)
                end == null -> HttpRange.UntilEnd(start)
                else -> HttpRange.Bounded(start, end)
            }
        } ?: when (malformedRanges) {
            HttpRange.MalformedBehavior.IgnoreRangeRequest -> return null
            HttpRange.MalformedBehavior.BadRequest -> throw BadRequestException("Malformed range specifier: $rStr")
        }
    }

public fun List<HttpRange>.mergeOverlaps(resourceSize: Long): List<HttpRange> = this
    .sortedBy { it.rangeStart(resourceSize) }
    .let { ranges ->
        if (ranges.isEmpty()) return@let ranges

        val merged = mutableListOf<HttpRange>()
        var current = ranges.first()

        for (next in ranges.drop(1)) {
            val currentEnd = current.rangeEnd(resourceSize)
            val nextStart = next.rangeStart(resourceSize)

            // Check if ranges overlap
            if (nextStart <= currentEnd) {
                // Merge: keep earlier start, take later end
                current = HttpRange.Bounded(
                    current.rangeStart(resourceSize),
                    current.rangeEnd(resourceSize)
                )
            } else {
                // No overlap, save current and move to next
                merged.add(current)
                current = next
            }
        }

        merged.add(current)
        merged
    }

public fun ByteArray.sliceArray(range: HttpRange): ByteArray = sliceArray(
    range.rangeStart(size.toLong()).toInt()..range.rangeEnd(size.toLong()).toInt()
)

public suspend fun TypedData.getRange(range: HttpRange, dataSize: Long): TypedData =
    TypedData(
        mediaType = mediaType,
        data = when (data) {
            is Data.Bytes, is Data.Text -> Data.Bytes(data.bytes().sliceArray(range))
            is Data.Sink, is Data.Source, is Data.Suspending, is Data.SuspendingProducer -> {
                // Stream the requested window through instead of buffering the whole body into the heap: pipe the
                // source into a slicing sink that drops everything before the range and forwards only the window.
                // (This still *reads* through the whole upstream — post-hoc slicing can't seek — but heap stays flat,
                // which is what matters for large media served from a streaming source.)
                val bytesToForward: Long = when (range) {
                    is HttpRange.Bounded -> range.size
                    is HttpRange.Last, is HttpRange.UntilEnd -> RANGE_UNTIL_END
                }
                val start = range.rangeStart(dataSize)
                Data.SuspendingProducer(size = bytesToForward.takeIf { it != RANGE_UNTIL_END }) { out ->
                    data.writeTo(RangeSlicingSink(out, skip = start, forward = bytesToForward))
                }
            }
        }
    )

public suspend fun TypedData.getRanges(
    ranges: List<HttpRange>,
    dataSize: Long,
    rangeBoundary: String = "CONTENT_BOUNDARY",
): TypedData {
    // Multi-range (multipart/byteranges) responses buffer the whole body once: the boundary framing interleaves
    // headers between arbitrary, possibly-overlapping windows, so a single streaming pass isn't enough. Multi-range
    // requests are rare; single-range seeking (the large-media case) streams via getRange without buffering.
    val raw = data.bytes()
    return TypedData(
        mediaType = MediaType.MultiPart.ByteRanges.copy(parameters = mapOf("boundary" to rangeBoundary)),
        data = Data.Sink { sink ->
            fun Sink.writeRangeHeaders(range: HttpRange) {
                writeString(rangeBoundary + LINE_FEED)
                writeString("${HttpHeader.ContentType}: $mediaType" + LINE_FEED)
                writeString("${HttpHeader.ContentRange}: bytes ${range.rangeStart(dataSize)}-${range.rangeEnd(dataSize)}/$dataSize" + LINE_FEED)
            }

            if (data is Data.Bytes || data is Data.Text || ranges.mergeOverlaps(dataSize).size != ranges.size) {
                // read all bytes if available or ranges overlap
                val bytes = raw

                for (range in ranges) {
                    sink.writeRangeHeaders(range)
                    sink.writeString(LINE_FEED)
                    sink.write(bytes.sliceArray(range))
                    sink.writeString(LINE_FEED)
                }
                sink.writeString(rangeBoundary)
            } else kotlinx.io.Buffer().apply { write(raw) }.let { source ->  // use source if possible
                var pos = 0L
                for (range in ranges) {
                    sink.writeRangeHeaders(range)
                    sink.writeString(LINE_FEED)
                    source.skip(range.rangeStart(dataSize) - pos)
                    when (range) {
                        is HttpRange.Bounded -> {
                            sink.write(source, range.size)
                            pos = range.rangeEnd + 1
                            sink.writeString(LINE_FEED)
                        }

                        is HttpRange.Last, is HttpRange.UntilEnd -> {
                            source.transferTo(sink)
                            sink.writeString(LINE_FEED)
                            break // If no ranges overlap this should be the last range regardless
                        }
                    }
                }
                sink.writeString(rangeBoundary)
            }
        }
    )
}

private const val LINE_FEED = "\r\n"