package com.lightningkite.lightningserver.data

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LongBitsTest {
    @Test
    fun testConstructorFromIterable() {
        val bits = LongBits(listOf(0, 5, 10, 15))
        assertTrue(0 in bits)
        assertFalse(1 in bits)
        assertTrue(5 in bits)
        assertTrue(10 in bits)
        assertTrue(15 in bits)
        assertFalse(16 in bits)
    }

    @Test
    fun testConstructorFromLong() {
        // 0b101 = indices 0 and 2 are set
        val bits = LongBits(0b101)
        assertTrue(0 in bits)
        assertFalse(1 in bits)
        assertTrue(2 in bits)
    }

    @Test
    fun testIterator() {
        val bits = LongBits(listOf(0, 5, 10, 15))
        val list = bits.toList()
        assertEquals(listOf(0, 5, 10, 15), list)
    }

    @Test
    fun testPlus() {
        val bits1 = LongBits(listOf(0, 5))
        val bits2 = LongBits(listOf(10, 15))
        val combined = bits1 + bits2

        assertTrue(0 in combined)
        assertTrue(5 in combined)
        assertTrue(10 in combined)
        assertTrue(15 in combined)
    }

    @Test
    fun testLowestIncluding() {
        val bits = LongBits(listOf(5, 10, 15))
        assertEquals(5, bits.lowestIncluding(0))
        assertEquals(5, bits.lowestIncluding(5))
        assertEquals(10, bits.lowestIncluding(6))
        assertEquals(15, bits.lowestIncluding(11))
        assertEquals(-1, bits.lowestIncluding(16))
    }

    @Test
    fun testLowestAfter() {
        val bits = LongBits(listOf(5, 10, 15))
        assertEquals(5, bits.lowestAfter(0))
        assertEquals(10, bits.lowestAfter(5))
        assertEquals(15, bits.lowestAfter(10))
        assertEquals(-1, bits.lowestAfter(15))
    }

    @Test
    fun testContains() {
        val bits = LongBits(listOf(0, 31, 62))
        assertTrue(0 in bits)
        assertTrue(31 in bits)
        assertTrue(62 in bits)
        assertFalse(1 in bits)
        assertFalse(30 in bits)
        assertFalse(63 in bits)
    }

    @Test
    fun testToString() {
        // Single values
        assertEquals("5", LongBits(listOf(5)).toString())

        // Range
        val range = LongBits(listOf(0, 1, 2))
        assertEquals("0-2", range.toString())

        // Mixed
        val mixed = LongBits(listOf(0, 1, 2, 5, 10, 11, 12))
        assertEquals("0-2,5,10-12", mixed.toString())
    }

    @Test
    fun testEmptyBits() {
        val bits = LongBits(emptyList())
        assertEquals(-1, bits.lowestIncluding(0))
        assertEquals(emptyList(), bits.toList())
    }

    @Test
    fun testAllBits() {
        // LongBits can store indices 0-63 (64 bits total)
        val bits = LongBits((0..63))
        // Check that all valid indices are set
        for (i in 0..63) {
            assertTrue(i in bits, "Index $i should be in bits")
        }
        assertEquals(0, bits.lowestIncluding(0))
        assertEquals(63, bits.lowestIncluding(63))
    }

    @Test
    fun testBitsWithoutIndex63() {
        // Test with indices 0-62 to avoid the sign bit issue
        val bits = LongBits((0..62))
        for (i in 0..62) {
            assertTrue(i in bits, "Index $i should be in bits")
        }
        assertEquals(0, bits.lowestIncluding(0))
        assertEquals(62, bits.lowestIncluding(62))
    }

    // ========== Additional tests (by Claude) ==========

    @Test
    fun `testSize returns number of set bits`() {
        // by Claude
        val bits = LongBits(listOf(0, 5, 10, 15))
        assertEquals(4, bits.size)
    }

    @Test
    fun `testSize returns 0 for empty bits`() {
        // by Claude
        val bits = LongBits(emptyList())
        assertEquals(0, bits.size)
    }

    @Test
    fun `testSize returns 64 for all bits set`() {
        // by Claude
        val bits = LongBits((0..63))
        assertEquals(64, bits.size)
    }

    @Test
    fun `testMinus removes bits`() {
        // by Claude
        val bits1 = LongBits(listOf(0, 5, 10, 15))
        val bits2 = LongBits(listOf(5, 10))
        val result = bits1.minus(bits2)

        assertTrue(0 in result)
        assertFalse(5 in result)
        assertFalse(10 in result)
        assertTrue(15 in result)
        assertEquals(2, result.size)
    }

    @Test
    fun `testMinus with no overlap`() {
        // by Claude
        val bits1 = LongBits(listOf(0, 5))
        val bits2 = LongBits(listOf(10, 15))
        val result = bits1.minus(bits2)

        assertEquals(listOf(0, 5), result.toList())
    }

    @Test
    fun `testIntersect returns common bits`() {
        // by Claude
        val bits1 = LongBits(listOf(0, 5, 10, 15))
        val bits2 = LongBits(listOf(5, 10, 20))
        val result = bits1.intersect(bits2)

        assertFalse(0 in result)
        assertTrue(5 in result)
        assertTrue(10 in result)
        assertFalse(15 in result)
        assertFalse(20 in result)
        assertEquals(2, result.size)
    }

    @Test
    fun `testIntersect with no overlap returns empty`() {
        // by Claude
        val bits1 = LongBits(listOf(0, 5))
        val bits2 = LongBits(listOf(10, 15))
        val result = bits1.intersect(bits2)

        assertTrue(result.isEmpty())
        assertEquals(0, result.size)
    }

    @Test
    fun `testIsEmpty returns true for empty bits`() {
        // by Claude
        val bits = LongBits(emptyList())
        assertTrue(bits.isEmpty())
    }

    @Test
    fun `testIsEmpty returns false for non-empty bits`() {
        // by Claude
        val bits = LongBits(listOf(5))
        assertFalse(bits.isEmpty())
    }

    @Test
    fun `testToString returns empty string for empty bits`() {
        // by Claude
        val bits = LongBits(emptyList())
        assertEquals("", bits.toString())
    }

}
