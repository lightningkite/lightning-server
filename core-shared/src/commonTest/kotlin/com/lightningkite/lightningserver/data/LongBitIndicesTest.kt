package com.lightningkite.lightningserver.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LongBitIndicesTest {

    @Test
    fun `indices are laid out most significant bit first`() {
        assertEquals(1L shl 63, longBitIndicesOf(0).mask)
        assertEquals(1L, longBitIndicesOf(63).mask)
        assertEquals(0b1010L shl 60, longBitIndicesOf(0, 2).mask)
        assertEquals(0L, LongBitIndices().mask)
    }

    @Test
    fun `every index round-trips in ascending order`() {
        val all = (0 until LongBitIndices.CAPACITY).toList()
        assertEquals(all, LongBitIndices.from(all.iterator()).toList())
        assertEquals(all, LongBitIndices(-1L).toList())
        assertEquals(listOf(3, 40, 63), longBitIndicesOf(63, 3, 40, 3).toList())
    }

    @Test
    fun `each single index round-trips`() {
        for (index in 0 until LongBitIndices.CAPACITY) {
            assertEquals(listOf(index), longBitIndicesOf(index).toList(), "index $index")
        }
    }

    @Test
    fun `size and isEmpty reflect the set bits`() {
        assertEquals(0, LongBitIndices().size)
        assertTrue(LongBitIndices().isEmpty())
        assertEquals(64, LongBitIndices(-1L).size)
        assertFalse(longBitIndicesOf(63).isEmpty())
    }

    @Test
    fun `get and contains report exactly the indices that were added`() {
        val bits = longBitIndicesOf(0, 31, 32, 63)
        listOf(0, 31, 32, 63).forEach {
            assertTrue(bits[it], "$it should be present")
            assertTrue(it in bits, "$it should be present")
        }
        listOf(1, 30, 33, 62).forEach {
            assertFalse(bits[it], "$it should be absent")
            assertFalse(it in bits, "$it should be absent")
        }
    }

    @Test
    fun `contains is false outside the range while get and from throw`() {
        val bits = LongBitIndices(-1L)
        assertFalse(-1 in bits)
        assertFalse(64 in bits)
        assertFailsWith<IndexOutOfBoundsException> { bits[-1] }
        assertFailsWith<IndexOutOfBoundsException> { bits[64] }
        assertFailsWith<IndexOutOfBoundsException> { longBitIndicesOf(64) }
        assertFailsWith<IndexOutOfBoundsException> { longBitIndicesOf(-1) }
    }

    @Test
    fun `containsAll checks a collection or another LongBitIndices`() {
        val bits = longBitIndicesOf(1, 40, 63)
        assertTrue(bits.containsAll(listOf(1, 63)))
        assertFalse(bits.containsAll(listOf(1, 64)))
        assertTrue(bits.containsAll(longBitIndicesOf(40, 63)))
        assertFalse(bits.containsAll(longBitIndicesOf(1, 2)))
    }

    @Test
    fun `iterator throws once exhausted`() {
        val iter = longBitIndicesOf(63).iterator()
        assertEquals(63, iter.next())
        assertFalse(iter.hasNext())
        assertFailsWith<NoSuchElementException> { iter.next() }
    }

    @Test
    fun `plus is a union`() {
        assertEquals(longBitIndicesOf(0, 40, 63), longBitIndicesOf(0, 40) + longBitIndicesOf(40, 63))
    }

    @Test
    fun `toString lists the indices`() {
        assertEquals("LongBitIndices[2, 63]", longBitIndicesOf(63, 2).toString())
    }
}
