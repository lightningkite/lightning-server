package com.lightningkite.lightningserver.audit

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.condition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitIndicesTest {

    @Test
    fun `indices are laid out most significant bit first`() {
        assertEquals(1 shl 31, bitIndicesOf(0).mask)
        assertEquals(1, bitIndicesOf(31).mask)
        assertEquals(0b1010 shl 28, bitIndicesOf(0, 2).mask)
        assertEquals(0, BitIndices().mask)
    }

    @Test
    fun `every index round-trips in ascending order`() {
        val all = (0 until BitIndices.CAPACITY).toList()
        assertEquals(all, BitIndices.from(all.iterator()).toList())
        assertEquals(all, BitIndices(-1).toList())
        assertEquals(listOf(3, 17, 31), bitIndicesOf(31, 3, 17, 3).toList())
    }

    @Test
    fun `each single index round-trips`() {
        for (index in 0 until BitIndices.CAPACITY) {
            assertEquals(listOf(index), bitIndicesOf(index).toList(), "index $index")
        }
    }

    @Test
    fun `size and isEmpty reflect the set bits`() {
        assertEquals(0, BitIndices().size)
        assertTrue(BitIndices().isEmpty())
        assertEquals(3, bitIndicesOf(0, 15, 31).size)
        assertFalse(bitIndicesOf(0).isEmpty())
    }

    @Test
    fun `get and contains report exactly the indices that were added`() {
        val bits = bitIndicesOf(0, 15, 31)
        listOf(0, 15, 31).forEach {
            assertTrue(bits[it], "$it should be present")
            assertTrue(it in bits, "$it should be present")
        }
        listOf(1, 14, 16, 30).forEach {
            assertFalse(bits[it], "$it should be absent")
            assertFalse(it in bits, "$it should be absent")
        }
    }

    @Test
    fun `contains is false outside the range while get throws`() {
        val bits = BitIndices(-1)
        assertFalse(-1 in bits)
        assertFalse(32 in bits)
        assertFailsWith<IndexOutOfBoundsException> { bits[-1] }
        assertFailsWith<IndexOutOfBoundsException> { bits[32] }
    }

    @Test
    fun `from rejects an index outside the range`() {
        assertFailsWith<IndexOutOfBoundsException> { bitIndicesOf(32) }
        assertFailsWith<IndexOutOfBoundsException> { bitIndicesOf(-1) }
    }

    @Test
    fun `containsAll of a collection checks every element`() {
        val bits = bitIndicesOf(1, 5, 31)
        assertTrue(bits.containsAll(listOf(1, 5)))
        assertTrue(bits.containsAll(listOf(1, 5, 31)))
        assertTrue(bits.containsAll(emptyList()))
        assertFalse(bits.containsAll(listOf(1, 2)))
        // An index's value must not be confused with its bit: 31 is the low bit, and 1 is not present.
        assertFalse(bitIndicesOf(31).containsAll(listOf(1)))
        assertFalse(bits.containsAll(listOf(1, 32)))
        assertFalse(bits.containsAll(listOf(-1)))
    }

    @Test
    fun `containsAll of another BitIndices is a subset check`() {
        val bits = bitIndicesOf(1, 5, 31)
        assertTrue(bits.containsAll(bitIndicesOf(1, 31)))
        assertTrue(bits.containsAll(BitIndices()))
        assertFalse(bits.containsAll(bitIndicesOf(1, 2)))
        assertFalse(BitIndices().containsAll(bitIndicesOf(0)))
    }

    @Test
    fun `plus is a union`() {
        assertEquals(bitIndicesOf(0, 4, 31), bitIndicesOf(0, 4) + bitIndicesOf(4, 31))
        assertEquals(bitIndicesOf(7), bitIndicesOf(7) + BitIndices())
    }

    @Test
    fun `toArray and toString list the indices`() {
        assertEquals(listOf(2, 9), bitIndicesOf(9, 2).toArray().toList())
        assertEquals("BitIndices[2, 9]", bitIndicesOf(9, 2).toString())
        assertEquals("BitIndices[]", BitIndices().toString())
    }

    @Test
    fun `iterator throws once exhausted`() {
        val iter = bitIndicesOf(3).iterator()
        assertEquals(3, iter.next())
        assertFalse(iter.hasNext())
        assertFailsWith<NoSuchElementException> { iter.next() }
    }

    @Test
    fun `containsAny condition matches a value holding any requested index`() {
        val condition = condition<BitIndices> { it.containsAny(bitIndicesOf(3, 31)) }
        assertTrue(condition(bitIndicesOf(3)))
        assertTrue(condition(bitIndicesOf(31)))
        assertTrue(condition(bitIndicesOf(0, 3, 31)))
        assertFalse(condition(bitIndicesOf(4, 30)))
        assertFalse(condition(BitIndices()))
    }

    @Test
    fun `containsAll condition matches only a value holding every requested index`() {
        val condition = condition<BitIndices> { it.containsAll(bitIndicesOf(0, 31)) }
        assertTrue(condition(bitIndicesOf(0, 31)))
        assertTrue(condition(bitIndicesOf(0, 5, 31)))
        assertFalse(condition(bitIndicesOf(0)))
        assertFalse(condition(bitIndicesOf(31)))
        assertFalse(condition(BitIndices()))
    }

    @Test
    fun `conditions query the mask directly`() {
        val condition = condition<BitIndices> { it.containsAll(bitIndicesOf(0)) }
        assertEquals(Condition.IntBitsSet(1 shl 31), (condition as Condition.OnField<*, *>).condition)
    }
}
