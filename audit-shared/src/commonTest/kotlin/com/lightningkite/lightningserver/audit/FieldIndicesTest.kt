package com.lightningkite.lightningserver.audit

import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.condition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FieldIndicesTest {

    private val empty = fieldIndicesOf()

    @Test
    fun `an index lands in the segment and position the layout promises`() {
        for (index in 0 until FieldIdentifiers.CAPACITY) {
            val indices = fieldIndicesOf(index)
            val s = index / BitIndices.CAPACITY
            assertEquals(bitIndicesOf(index % BitIndices.CAPACITY), indices.segment(s), "index $index landed wrong")
            for (other in 0 until FieldIdentifiers.SEGMENTS) {
                if (other != s) assertTrue(indices.segment(other).isEmpty(), "index $index leaked into segment $other")
            }
        }
    }

    @Test
    fun `each single index round-trips through iteration`() {
        for (index in 0 until FieldIdentifiers.CAPACITY) {
            assertEquals(listOf(index), fieldIndicesOf(index).toList(), "index $index")
        }
    }

    @Test
    fun `every index round-trips in ascending order`() {
        val all = (0 until FieldIdentifiers.CAPACITY).toList()
        assertEquals(all, FieldIdentifiers.from(all.iterator()).toList())
        assertEquals(listOf(0, 31, 32, 63), fieldIndicesOf(63, 32, 31, 0).toList())
    }

    @Test
    fun `iteration skips empty segments`() {
        assertEquals(emptyList(), empty.toList())
        assertEquals(listOf(5), fieldIndicesOf(5).toList())
        assertEquals(listOf(40), fieldIndicesOf(40).toList())
    }

    @Test
    fun `iterator throws once exhausted`() {
        val iter = fieldIndicesOf(5).iterator()
        assertEquals(5, iter.next())
        assertFalse(iter.hasNext())
        assertFailsWith<NoSuchElementException> { iter.next() }
        assertFailsWith<NoSuchElementException> { empty.iterator().next() }
    }

    @Test
    fun `size and isEmpty span every segment`() {
        assertEquals(0, empty.size)
        assertTrue(empty.isEmpty())
        assertEquals(4, fieldIndicesOf(0, 31, 32, 63).size)
        assertFalse(fieldIndicesOf(63).isEmpty())
    }

    @Test
    fun `get and contains report exactly the indices that were added`() {
        val indices = fieldIndicesOf(0, 31, 32, 63)
        listOf(0, 31, 32, 63).forEach {
            assertTrue(indices[it], "$it should be present")
            assertTrue(it in indices, "$it should be present")
        }
        listOf(1, 30, 33, 62).forEach {
            assertFalse(indices[it], "$it should be absent")
            assertFalse(it in indices, "$it should be absent")
        }
    }

    @Test
    fun `contains is false outside the capacity while get throws`() {
        val full = FieldIdentifiers.from((0 until FieldIdentifiers.CAPACITY).iterator())
        assertFalse(-1 in full)
        assertFalse(FieldIdentifiers.CAPACITY in full)
        assertFailsWith<IndexOutOfBoundsException> { full[-1] }
        assertFailsWith<IndexOutOfBoundsException> { full[FieldIdentifiers.CAPACITY] }
    }

    @Test
    fun `from rejects an index outside the capacity`() {
        assertFailsWith<IndexOutOfBoundsException> { fieldIndicesOf(FieldIdentifiers.CAPACITY) }
        assertFailsWith<IndexOutOfBoundsException> { fieldIndicesOf(-1) }
    }

    @Test
    fun `containsAll checks every segment`() {
        val indices = fieldIndicesOf(1, 31, 40)
        assertTrue(indices.containsAll(listOf(1, 40)))
        assertTrue(indices.containsAll(emptyList()))
        assertFalse(indices.containsAll(listOf(1, 41)))
        assertFalse(indices.containsAll(listOf(1, FieldIdentifiers.CAPACITY)))
        assertFalse(indices.containsAll(listOf(-1)))

        assertTrue(indices.containsAll(fieldIndicesOf(31, 40)))
        assertTrue(indices.containsAll(empty))
        assertFalse(indices.containsAll(fieldIndicesOf(2)))
        assertFalse(indices.containsAll(fieldIndicesOf(63)))
    }

    @Test
    fun `plus merges every segment`() {
        val merged = fieldIndicesOf(1, 40) + fieldIndicesOf(7, 60)
        assertEquals(listOf(1, 7, 40, 60), merged.toList())
        assertEquals(merged, merged + empty)
    }

    @Test
    fun `segments lists each segment in order`() {
        val indices = fieldIndicesOf(1, 40)
        assertEquals(listOf(indices.fields0, indices.fields1), indices.segments())
        assertFailsWith<IndexOutOfBoundsException> { indices.segment(FieldIdentifiers.SEGMENTS) }
    }

    @Test
    fun `containsAny condition matches a value holding any requested index`() {
        val condition = condition<FieldIdentifiers> { it.containsAny(fieldIndicesOf(3, 40)) }
        assertTrue(condition(fieldIndicesOf(3)))
        assertTrue(condition(fieldIndicesOf(40)))
        assertTrue(condition(fieldIndicesOf(3, 40)))
        assertFalse(condition(fieldIndicesOf(4, 41)))
        assertFalse(condition(empty))
    }

    @Test
    fun `containsAll condition matches only a value holding every requested index`() {
        val condition = condition<FieldIdentifiers> { it.containsAll(fieldIndicesOf(3, 40)) }
        assertTrue(condition(fieldIndicesOf(3, 40)))
        assertTrue(condition(fieldIndicesOf(3, 40, 50)), "extra indices must not exclude a value")
        assertFalse(condition(fieldIndicesOf(3)))
        assertFalse(condition(fieldIndicesOf(40)))
        assertFalse(condition(empty))
    }

    @Test
    fun `conditions only touch the segments the query uses`() {
        val any = condition<FieldIdentifiers> { it.containsAny(fieldIndicesOf(1, 33)) }
        assertEquals(2, (any as Condition.Or).conditions.size, "expected one condition per segment; was $any")

        val all = condition<FieldIdentifiers> { it.containsAll(fieldIndicesOf(1, 2)) }
        assertTrue(all !is Condition.And, "same-segment indices belong in one mask; was $all")
    }

    @Test
    fun `an empty query is the identity of its operator`() {
        assertEquals(Condition.Always, condition<FieldIdentifiers> { it.containsAll(empty) })
        assertEquals(Condition.Never, condition<FieldIdentifiers> { it.containsAny(empty) })
    }
}
