package com.lightningkite.lightningserver.files

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.services.data.*
import kotlinx.io.Sink
import kotlinx.io.writeString

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
        val size: Long get() = rangeEnd - rangeStart

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

/**
 * Narrows this data down to a single requested [range].
 *
 * Streaming data is read through its source, which is acquired here rather than inside the produced
 * [Data.Sink] because that producer is blocking - the bytes themselves are still only pulled when the
 * response is written.
 */
public suspend fun TypedData.getRange(range: HttpRange, dataSize: Long): TypedData =
    TypedData(
        mediaType = mediaType,
        data = when (data) {
            is Data.Bytes, is Data.Text -> Data.Bytes(data.bytes().sliceArray(range))
            else -> data.source().let { opened ->
                Data.Sink { sink ->
                    opened.use { source ->
                        source.skip(range.rangeStart(dataSize))
                        when (range) {
                            is HttpRange.Bounded -> sink.write(source, range.size)
                            is HttpRange.Last, is HttpRange.UntilEnd -> source.transferTo(sink)
                        }
                    }
                }
            }
        }
    )

/**
 * Packs the requested [ranges] into a `multipart/byteranges` body.
 *
 * As in [getRange], the bytes or the source are acquired here because the produced [Data.Sink]'s
 * producer is blocking.
 */
public suspend fun TypedData.getRanges(
    ranges: List<HttpRange>,
    dataSize: Long,
    rangeBoundary: String = "CONTENT_BOUNDARY",
): TypedData {
    fun Sink.writeRangeHeaders(range: HttpRange) {
        writeString(rangeBoundary + LINE_FEED)
        writeString("${HttpHeader.ContentType}: $mediaType" + LINE_FEED)
        writeString("${HttpHeader.ContentRange}: bytes ${range.rangeStart(dataSize)}-${range.rangeEnd(dataSize)}/$dataSize" + LINE_FEED)
    }

    val ranged = if (data is Data.Bytes || data is Data.Text || ranges.mergeOverlaps(dataSize).size != ranges.size) {
        // read all bytes if available or ranges overlap
        val bytes = data.bytes()
        Data.Sink { sink ->
            for (range in ranges) {
                sink.writeRangeHeaders(range)
                sink.writeString(LINE_FEED)
                sink.write(bytes.sliceArray(range))
                sink.writeString(LINE_FEED)
            }
            sink.writeString(rangeBoundary)
        }
    } else {
        val opened = data.source()  // use source if possible
        Data.Sink { sink ->
            opened.use { source ->
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
    }

    return TypedData(
        mediaType = MediaType.MultiPart.ByteRanges.copy(parameters = mapOf("boundary" to rangeBoundary)),
        data = ranged
    )
}

private const val LINE_FEED = "\r\n"