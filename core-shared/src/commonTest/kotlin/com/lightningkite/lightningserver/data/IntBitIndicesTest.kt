package com.lightningkite.lightningserver.data

import com.lightningkite.services.database.Condition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntBitIndicesTest {

    @Test
    fun `indices are laid out most significant bit first`() {
        assertEquals(1 shl 31, intBitIndicesOf(0).mask)
        assertEquals(1, intBitIndicesOf(31).mask)
        assertEquals(0b1010 shl 28, intBitIndicesOf(0, 2).mask)
        assertEquals(0, IntBitIndices().mask)
    }

    @Test
    fun `every index round-trips in ascending order`() {
        val all = (0 until IntBitIndices.CAPACITY).toList()
        assertEquals(all, IntBitIndices.from(all.iterator()).toList())
        assertEquals(all, IntBitIndices(-1).toList())
        assertEquals(listOf(3, 17, 31), intBitIndicesOf(31, 3, 17, 3).toList())
    }

    @Test
    fun `each single index round-trips`() {
        for (index in 0 until IntBitIndices.CAPACITY) {
            assertEquals(listOf(index), intBitIndicesOf(index).toList(), "index $index")
        }
    }

    @Test
    fun `size and isEmpty reflect the set bits`() {
        assertEquals(0, IntBitIndices().size)
        assertTrue(IntBitIndices().isEmpty())
        assertEquals(3, intBitIndicesOf(0, 15, 31).size)
        assertFalse(intBitIndicesOf(0).isEmpty())
    }

    @Test
    fun `get and contains report exactly the indices that were added`() {
        val bits = intBitIndicesOf(0, 15, 31)
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
        val bits = IntBitIndices(-1)
        assertFalse(-1 in bits)
        assertFalse(32 in bits)
        assertFailsWith<IndexOutOfBoundsException> { bits[-1] }
        assertFailsWith<IndexOutOfBoundsException> { bits[32] }
    }

    @Test
    fun `from rejects an index outside the range`() {
        assertFailsWith<IndexOutOfBoundsException> { intBitIndicesOf(32) }
        assertFailsWith<IndexOutOfBoundsException> { intBitIndicesOf(-1) }
    }

    @Test
    fun `containsAll of a collection checks every element`() {
        val bits = intBitIndicesOf(1, 5, 31)
        assertTrue(bits.containsAll(listOf(1, 5)))
        assertTrue(bits.containsAll(listOf(1, 5, 31)))
        assertTrue(bits.containsAll(emptyList()))
        assertFalse(bits.containsAll(listOf(1, 2)))
        // An index's value must not be confused with its bit: 31 is the low bit, and 1 is not present.
        assertFalse(intBitIndicesOf(31).containsAll(listOf(1)))
        assertFalse(bits.containsAll(listOf(1, 32)))
        assertFalse(bits.containsAll(listOf(-1)))
    }

    @Test
    fun `containsAll of another IntBitIndices is a subset check`() {
        val bits = intBitIndicesOf(1, 5, 31)
        assertTrue(bits.containsAll(intBitIndicesOf(1, 31)))
        assertTrue(bits.containsAll(IntBitIndices()))
        assertFalse(bits.containsAll(intBitIndicesOf(1, 2)))
        assertFalse(IntBitIndices().containsAll(intBitIndicesOf(0)))
    }

    @Test
    fun `plus is a union`() {
        assertEquals(intBitIndicesOf(0, 4, 31), intBitIndicesOf(0, 4) + intBitIndicesOf(4, 31))
        assertEquals(intBitIndicesOf(7), intBitIndicesOf(7) + IntBitIndices())
    }

    @Test
    fun `toIntArray and toString list the indices`() {
        assertEquals(listOf(2, 9), intBitIndicesOf(9, 2).toIntArray().toList())
        assertEquals("IntBitIndices[2, 9]", intBitIndicesOf(9, 2).toString())
        assertEquals("IntBitIndices[]", IntBitIndices().toString())
    }

    @Test
    fun `iterator throws once exhausted`() {
        val iter = intBitIndicesOf(3).iterator()
        assertEquals(3, iter.next())
        assertFalse(iter.hasNext())
        assertFailsWith<NoSuchElementException> { iter.next() }
    }

    @Test
    fun `containsAny condition matches a value holding any requested index`() {
        val condition = Condition<IntBitIndices> { it.containsAny(intBitIndicesOf(3, 31)) }
        assertTrue(condition(intBitIndicesOf(3)))
        assertTrue(condition(intBitIndicesOf(31)))
        assertTrue(condition(intBitIndicesOf(0, 3, 31)))
        assertFalse(condition(intBitIndicesOf(4, 30)))
        assertFalse(condition(IntBitIndices()))
    }

    @Test
    fun `containsAll condition matches only a value holding every requested index`() {
        val condition = Condition<IntBitIndices> { it.containsAll(intBitIndicesOf(0, 31)) }
        assertTrue(condition(intBitIndicesOf(0, 31)))
        assertTrue(condition(intBitIndicesOf(0, 5, 31)))
        assertFalse(condition(intBitIndicesOf(0)))
        assertFalse(condition(intBitIndicesOf(31)))
        assertFalse(condition(IntBitIndices()))
    }

    @Test
    fun `conditions query the mask directly`() {
        val condition = Condition<IntBitIndices> { it.containsAll(intBitIndicesOf(0)) }
        assertEquals(Condition.IntBitsSet(1 shl 31), (condition as Condition.OnField<*, *>).condition)
    }
}
