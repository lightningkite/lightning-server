package com.lightningkite.lightningserver.files

import com.lightningkite.services.data.Data
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Tests for [TypedData.getRange]. Covers all three [HttpRange] forms
 * (Bounded, UntilEnd, Last) against each [Data] variant after the
 * service-abstractions Data API upgrade.
 */
class TypedDataGetRangeTest {

    private val payload = ByteArray(256) { it.toByte() }
    private val size: Long = payload.size.toLong()

    private fun typedBytes(): TypedData =
        TypedData(Data.Bytes(payload), MediaType.Application.OctetStream)

    private fun typedText(text: String): TypedData =
        TypedData(Data.Text(text), MediaType.Text.Plain)

    private fun typedSource(): TypedData =
        TypedData(Data.Source(Buffer().also { it.write(payload) }, size), MediaType.Application.OctetStream)

    private fun typedSink(): TypedData =
        TypedData(Data.Sink(size) { it.write(payload) }, MediaType.Application.OctetStream)

    // -------- Bytes --------

    @Test
    fun bounded_on_bytes_slices_inclusive(): Unit = runBlocking {
        val out = typedBytes().getRange(HttpRange.Bounded(0, 9), size).data.bytes()
        assertEquals(10, out.size)
        assertEquals(payload.sliceArray(0..9).toList(), out.toList())
    }

    @Test
    fun until_end_on_bytes_returns_tail(): Unit = runBlocking {
        val out = typedBytes().getRange(HttpRange.UntilEnd(250), size).data.bytes()
        assertEquals(payload.sliceArray(250..255).toList(), out.toList())
    }

    @Test
    fun last_on_bytes_returns_suffix(): Unit = runBlocking {
        val out = typedBytes().getRange(HttpRange.Last(8), size).data.bytes()
        assertEquals(payload.sliceArray(248..255).toList(), out.toList())
    }

    @Test
    fun bounded_on_bytes_preserves_media_type(): Unit = runBlocking {
        val original = typedBytes()
        val ranged = original.getRange(HttpRange.Bounded(0, 1), size)
        assertSame(original.mediaType, ranged.mediaType)
    }

    // -------- Text --------

    @Test
    fun bounded_on_text_slices_underlying_bytes(): Unit = runBlocking {
        val text = "abcdefghij" // 10 bytes ASCII
        val out = typedText(text).getRange(HttpRange.Bounded(2, 5), text.length.toLong()).data.bytes()
        assertEquals("cdef", out.decodeToString())
    }

    // -------- Source --------

    @Test
    fun bounded_on_source_slices_inclusive(): Unit = runBlocking {
        val out = typedSource().getRange(HttpRange.Bounded(10, 19), size).data.bytes()
        // Bounded.size == rangeEnd - rangeStart, so 19-10 = 9 bytes emitted.
        // This matches the current contract of getRange for streaming data.
        assertEquals(9, out.size)
        assertEquals(payload.sliceArray(10..18).toList(), out.toList())
    }

    @Test
    fun until_end_on_source_returns_tail(): Unit = runBlocking {
        val out = typedSource().getRange(HttpRange.UntilEnd(200), size).data.bytes()
        assertEquals(payload.sliceArray(200..255).toList(), out.toList())
    }

    @Test
    fun last_on_source_returns_suffix(): Unit = runBlocking {
        val out = typedSource().getRange(HttpRange.Last(16), size).data.bytes()
        assertEquals(payload.sliceArray(240..255).toList(), out.toList())
    }

    @Test
    fun source_range_produces_sink_data(): Unit = runBlocking {
        val ranged = typedSource().getRange(HttpRange.Bounded(0, 1), size)
        assert(ranged.data is Data.Sink) { "Expected ranged source to become Data.Sink, got ${ranged.data::class}" }
    }

    // -------- Sink --------

    @Test
    fun bounded_on_sink_slices_inclusive(): Unit = runBlocking {
        val out = typedSink().getRange(HttpRange.Bounded(100, 109), size).data.bytes()
        assertEquals(9, out.size)
        assertEquals(payload.sliceArray(100..108).toList(), out.toList())
    }

    @Test
    fun until_end_on_sink_returns_tail(): Unit = runBlocking {
        val out = typedSink().getRange(HttpRange.UntilEnd(128), size).data.bytes()
        assertEquals(payload.sliceArray(128..255).toList(), out.toList())
    }

    @Test
    fun last_on_sink_returns_suffix(): Unit = runBlocking {
        val out = typedSink().getRange(HttpRange.Last(32), size).data.bytes()
        assertEquals(payload.sliceArray(224..255).toList(), out.toList())
    }

    @Test
    fun sink_range_produces_sink_data(): Unit = runBlocking {
        val ranged = typedSink().getRange(HttpRange.UntilEnd(0), size)
        assert(ranged.data is Data.Sink) { "Expected ranged sink to remain Data.Sink, got ${ranged.data::class}" }
    }

    // -------- Edge cases --------

    @Test
    fun single_byte_bounded_on_bytes(): Unit = runBlocking {
        val out = typedBytes().getRange(HttpRange.Bounded(42, 42), size).data.bytes()
        assertEquals(1, out.size)
        assertEquals(payload[42], out[0])
    }

    @Test
    fun until_end_from_zero_returns_full_payload_for_source(): Unit = runBlocking {
        val out = typedSource().getRange(HttpRange.UntilEnd(0), size).data.bytes()
        assertEquals(payload.toList(), out.toList())
    }

    @Test
    fun last_full_size_returns_full_payload_for_sink(): Unit = runBlocking {
        val out = typedSink().getRange(HttpRange.Last(size), size).data.bytes()
        assertEquals(payload.toList(), out.toList())
    }
}
