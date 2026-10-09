package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.Unsafe
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.InlineProperty
import com.lightningkite.services.database.condition
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FieldIdentifierSetTests {

    private fun fieldIds(vararg raw: Int) = FieldIdentifierSet.from(raw.map(::ModelFieldId))

    private fun FieldIdentifierSet.raw(): List<Int> = map { it.raw }

    private val empty = FieldIdentifierSet.EMPTY

    @Test
    fun `an index lands in the segment and position the layout promises`() {
        for (index in 0 until FieldIdentifierSet.CAPACITY) {
            val indices = fieldIds(index)
            val s = index / BitIndices.CAPACITY
            assertEquals(bitIndicesOf(index % BitIndices.CAPACITY), indices.segment(s), "index $index landed wrong")
            for (other in 0 until FieldIdentifierSet.SEGMENTS) {
                if (other != s) assertTrue(indices.segment(other).isEmpty(), "index $index leaked into segment $other")
            }
        }
    }

    @Test
    fun `each single index round-trips through iteration`() {
        for (index in 0 until FieldIdentifierSet.CAPACITY) {
            assertEquals(listOf(index), fieldIds(index).raw(), "index $index")
        }
    }

    @Test
    fun `every index round-trips in ascending order`() {
        val all = (0 until FieldIdentifierSet.CAPACITY).toList()
        assertEquals(all, FieldIdentifierSet.from(all.map(::ModelFieldId)).raw())
        assertEquals(listOf(0, 31, 32, 63), fieldIds(63, 32, 31, 0).raw())
    }

    @Test
    fun `iteration skips empty segments`() {
        assertEquals(emptyList(), empty.raw())
        assertEquals(listOf(5), fieldIds(5).raw())
        assertEquals(listOf(40), fieldIds(40).raw())
    }

    @Test
    fun `iterator throws once exhausted`() {
        val iter = fieldIds(5).iterator()
        assertEquals(ModelFieldId(5), iter.next())
        assertFalse(iter.hasNext())
        assertFailsWith<NoSuchElementException> { iter.next() }
        assertFailsWith<NoSuchElementException> { empty.iterator().next() }
    }

    @Test
    fun `size and isEmpty span every segment`() {
        assertEquals(0, empty.size)
        assertTrue(empty.isEmpty())
        assertEquals(4, fieldIds(0, 31, 32, 63).size)
        assertFalse(fieldIds(63).isEmpty())
    }

    @Test
    fun `get and contains report exactly the indices that were added`() {
        val indices = fieldIds(0, 31, 32, 63)
        listOf(0, 31, 32, 63).map(::ModelFieldId).forEach {
            assertTrue(indices[it], "$it should be present")
            assertTrue(it in indices, "$it should be present")
        }
        listOf(1, 30, 33, 62).map(::ModelFieldId).forEach {
            assertFalse(indices[it], "$it should be absent")
            assertFalse(it in indices, "$it should be absent")
        }
    }

    @Test
    fun `an id outside the capacity cannot be constructed`() {
        assertFailsWith<IllegalArgumentException> { ModelFieldId(-1) }
        assertFailsWith<IllegalArgumentException> { ModelFieldId(FieldIdentifierSet.CAPACITY) }
        ModelFieldId(FieldIdentifierSet.CAPACITY - 1)
    }

    @Test
    fun `an out of range id is rejected when decoded`() {
        assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString(ModelFieldId.serializer(), "${FieldIdentifierSet.CAPACITY}")
        }
    }

    @Test
    fun `containsAll checks every segment`() {
        val indices = fieldIds(1, 31, 40)
        assertTrue(indices.containsAll(listOf(1, 40).map(::ModelFieldId)))
        assertTrue(indices.containsAll(emptyList<ModelFieldId>()))
        assertFalse(indices.containsAll(listOf(1, 41).map(::ModelFieldId)))

        assertTrue(indices.containsAll(fieldIds(31, 40)))
        assertTrue(indices.containsAll(empty))
        assertFalse(indices.containsAll(fieldIds(2)))
        assertFalse(indices.containsAll(fieldIds(63)))
    }

    @Test
    fun `plus merges every segment`() {
        val merged = fieldIds(1, 40) + fieldIds(7, 60)
        assertEquals(listOf(1, 7, 40, 60), merged.raw())
        assertEquals(merged, merged + empty)
        assertEquals(fieldIds(1, 40, 7), fieldIds(1, 40) + ModelFieldId(7))
    }

    @Test
    fun `segments lists each segment in order`() {
        val indices = fieldIds(1, 40)
        assertEquals(listOf(indices.fields0, indices.fields1), indices.segments())
        assertFailsWith<IndexOutOfBoundsException> { indices.segment(FieldIdentifierSet.SEGMENTS) }
    }

    @Test
    fun `containsAny condition matches a value holding any requested index`() {
        val condition = condition<FieldIdentifierSet> { it.containsAny(fieldIds(3, 40)) }
        assertTrue(condition(fieldIds(3)))
        assertTrue(condition(fieldIds(40)))
        assertTrue(condition(fieldIds(3, 40)))
        assertFalse(condition(fieldIds(4, 41)))
        assertFalse(condition(empty))
    }

    @Test
    fun `containsAll condition matches only a value holding every requested index`() {
        val condition = condition<FieldIdentifierSet> { it.containsAll(fieldIds(3, 40)) }
        assertTrue(condition(fieldIds(3, 40)))
        assertTrue(condition(fieldIds(3, 40, 50)), "extra indices must not exclude a value")
        assertFalse(condition(fieldIds(3)))
        assertFalse(condition(fieldIds(40)))
        assertFalse(condition(empty))
    }

    @Test
    fun `conditions only touch the segments the query uses`() {
        val any = condition<FieldIdentifierSet> { it.containsAny(fieldIds(1, 33)) }
        assertEquals(2, (any as Condition.Or).conditions.size, "expected one condition per segment; was $any")

        val all = condition<FieldIdentifierSet> { it.containsAll(fieldIds(1, 2)) }
        assertTrue(all !is Condition.And, "same-segment indices belong in one mask; was $all")
    }

    /** A field past the first segment is the case a single-Int layout would silently drop. */
    @Test
    fun `queries reach ids in the second segment alone`() {
        assertTrue(condition<FieldIdentifierSet> { it.containsAny(fieldIds(40)) }(fieldIds(40)))
        assertTrue(condition<FieldIdentifierSet> { it.containsAll(fieldIds(40, 50)) }(fieldIds(40, 50)))
        assertFalse(condition<FieldIdentifierSet> { it.containsAll(fieldIds(40, 50)) }(fieldIds(40)))
    }

    /**
     * Ids 0 and 32 are the top bit of their segment, which makes the mask a negative Int. Whether a
     * query engine handles that is the database layer's job; putting the bit in the right segment
     * with the right mask is this one's, so the built condition is asserted rather than evaluated.
     */
    @OptIn(InlineProperty::class)
    @Test
    fun `the top bit of a segment produces the mask and segment the layout promises`() {
        assertEquals(
            Condition.OnField(FieldIdentifierSet.fields1, Condition.OnField(BitIndices.mask, Condition.IntBitsAnySet(1 shl 31))),
            condition<FieldIdentifierSet> { it.containsAny(fieldIds(32)) },
        )
        assertEquals(
            Condition.OnField(FieldIdentifierSet.fields0, Condition.OnField(BitIndices.mask, Condition.IntBitsSet(1 shl 31))),
            condition<FieldIdentifierSet> { it.containsAll(fieldIds(0)) },
        )
    }

    @OptIn(Unsafe::class)
    private fun record(vararg ids: Int) = DisclosureRecord(
        _id = DisclosureRecord.ID(UuidV7.generate()),
        requestId = OriginRecord.ID(ExecutionId(UuidV7.generate())),
        recordType = ModelTypeId(0),
        recordId = Uuid.NIL,
        disclosed = fieldIds(*ids),
    )

    /** Through the record's path, which is how real queries reach the set. */
    @Test
    fun `conditions on a record's disclosed fields match the records they should`() {
        val all = condition<DisclosureRecord> { it.disclosed.containsAll(fieldIds(3, 40)) }
        assertTrue(all(record(3, 40)))
        assertTrue(all(record(3, 40, 50)), "extra disclosed fields must not exclude a record")
        assertFalse(all(record(3)))
        assertFalse(all(record()))

        val any = condition<DisclosureRecord> { it.disclosed.containsAny(fieldIds(3, 40)) }
        assertTrue(any(record(40)))
        assertFalse(any(record(4, 41)))
        assertFalse(any(record()))
    }

    @Test
    fun `an empty query is the identity of its operator`() {
        assertEquals(Condition.Always, condition<FieldIdentifierSet> { it.containsAll(empty) })
        assertEquals(Condition.Never, condition<FieldIdentifierSet> { it.containsAny(empty) })
    }
}
