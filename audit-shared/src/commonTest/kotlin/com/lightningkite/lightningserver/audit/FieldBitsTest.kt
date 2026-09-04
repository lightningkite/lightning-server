package com.lightningkite.lightningserver.audit

import com.lightningkite.services.database.Condition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FieldBitsTest {

    @Test
    fun `a bit index lands in the column and position the layout promises`() {
        for (index in 0 until FieldBits.CAPACITY) {
            val bits = FieldBits.EMPTY + index
            assertEquals(1 shl (index % 32), bits.column(index / 32), "index $index landed wrong")
            for (other in 0 until FieldBits.COLUMNS) {
                if (other != index / 32) assertEquals(0, bits.column(other), "index $index leaked into column $other")
            }
        }
    }

    @Test
    fun `every index round-trips through the whole capacity`() {
        val all = (0 until FieldBits.CAPACITY).toList()
        assertEquals(all, FieldBits.of(all).indices())
    }

    @Test
    fun `contains reports exactly the indices that were added`() {
        val bits = FieldBits.of(listOf(0, 31, 32, 63))
        listOf(0, 31, 32, 63).forEach { assertTrue(it in bits, "$it should be present") }
        listOf(1, 30, 33, 62).forEach { assertFalse(it in bits, "$it should be absent") }
    }

    @Test
    fun `union merges every column`() {
        val merged = FieldBits.of(listOf(1, 40)) + FieldBits.of(listOf(7, 60))
        assertEquals(listOf(1, 7, 40, 60), merged.indices())
    }

    @Test
    fun `an index outside the capacity is rejected rather than silently wrapping`() {
        assertFailsWith<IllegalArgumentException> { FieldBits.EMPTY + FieldBits.CAPACITY }
        assertFailsWith<IllegalArgumentException> { FieldBits.EMPTY + -1 }
    }

    private fun record(vararg indices: Int): DisclosureRecord {
        val bits = FieldBits.of(indices.toList())
        return DisclosureRecord(
            _id = Uuid.NIL,
            requestId = Uuid.NIL,
            modelId = 0,
            fields0 = bits.fields0,
            fields1 = bits.fields1,
            recordId = Uuid.NIL,
        )
    }

    /**
     * Asserted against the in-memory evaluator rather than by comparing condition trees, so the test
     * pins what the query *means* rather than how it happens to be built.
     */
    @Test
    fun `disclosedAll matches only records holding every requested field`() {
        val condition: Condition<DisclosureRecord> = disclosedAll(listOf(3, 40))

        assertTrue(condition(record(3, 40)))
        assertTrue(condition(record(3, 40, 50)), "extra disclosed fields must not exclude a record")
        assertFalse(condition(record(3)))
        assertFalse(condition(record(40)))
        assertFalse(condition(record()))
    }

    @Test
    fun `disclosedAny matches a record holding any one of the requested fields`() {
        val condition: Condition<DisclosureRecord> = disclosedAny(listOf(3, 40))

        assertTrue(condition(record(3)))
        assertTrue(condition(record(40)))
        assertTrue(condition(record(3, 40)))
        assertFalse(condition(record(4, 41)))
        assertFalse(condition(record()))
    }

    /** A field past the first column is the case a single-Int layout would silently drop. */
    @Test
    fun `queries reach fields in the second column`() {
        assertTrue(disclosedAny(listOf(40))(record(40)))
        assertTrue(disclosedAll(listOf(40, 50))(record(40, 50)))
        assertFalse(disclosedAll(listOf(40, 50))(record(40)))
    }

    /**
     * The top bit of each column is asserted on the condition that gets built rather than on what
     * evaluating it returns.
     *
     * Bit 31 of a column makes the mask a negative Int, and whether a query engine handles that
     * correctly is the database layer's contract, not this module's. What is this module's job is
     * putting the bit in the right column with the right mask, which is what these check.
     */
    @Test
    fun `the top bit of a column produces the mask and column the layout promises`() {
        assertEquals(
            Condition.OnField(DisclosureRecord_fields1, Condition.IntBitsAnySet(1 shl 31)),
            (disclosedAny(listOf(63)) as Condition.Or).conditions.single(),
        )
        assertEquals(
            Condition.OnField(DisclosureRecord_fields0, Condition.IntBitsSet(1 shl 31)),
            (disclosedAll(listOf(31)) as Condition.And).conditions.single(),
        )
    }

    @Test
    fun `a query spanning columns produces one condition per column touched`() {
        val any = disclosedAny(listOf(1, 33)) as Condition.Or
        assertEquals(2, any.conditions.size, "expected one condition per column; was $any")

        val all = disclosedAll(listOf(1, 2)) as Condition.And
        assertEquals(1, all.conditions.size, "same-column indices belong in one mask; was $all")
    }

    @Test
    fun `an empty query is the identity of its operator`() {
        assertEquals(Condition.Always, disclosedAll(emptyList()))
        assertEquals(Condition.Never, disclosedAny(emptyList()))
    }
}
