package com.lightningkite.lightningdb.test

import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import org.junit.Test
import kotlin.test.assertEquals

class UpdateRestrictionsTest {
    @Test fun test() {
        val restrictions = updateRestrictions<LargeTestModel> {
            it.embedded.cannotBeModified()
        }
        assertEquals(Condition.Never, restrictions(modification { it.embedded assign ClassUsedForEmbedding() }))
        assertEquals(Condition.Never, restrictions(modification { it.embedded.value2 assign 2 }))
        assertEquals(Condition.Always, restrictions(modification { it.int assign 2 }))
    }

    private fun <T> assertNever(restrictions: UpdateRestrictions<T>, modification: Modification<T>) =
        assertEquals(Condition.Never, restrictions(modification), "$modification should be refused")

    @Test
    fun `a rule on a child applies when writing its parent or the whole row`() {
        val admin = condition<LargeTestModel> { it.boolean eq true }
        val restrictions = updateRestrictions<LargeTestModel> { it.embedded.value2 requires admin }

        assertEquals(Condition.Always, restrictions(modification { it.embedded.value1 assign "x" }))
        assertEquals(admin, restrictions(modification { it.embedded assign ClassUsedForEmbedding() }))
        assertEquals(admin, restrictions(Modification.Assign(LargeTestModel())))
    }

    @Test
    fun `a modification that writes nothing trips no rule`() {
        val restrictions = updateRestrictions<LargeTestModel> { it.int.cannotBeModified() }
        assertEquals(Condition.Always, restrictions(Modification.Nothing.invoke()))
        assertEquals(Condition.Always, restrictions(Modification.Chain(listOf(Modification.Nothing.invoke(), modification { it.short assign 1 }))))
    }

    @Test
    fun `mustBe with And on a parent checks the written child`() {
        val restrictions = updateRestrictions<LargeTestModel> {
            it.embedded.mustBe { (it.value1 eq "CO") and (it.value2 neq 0) }
        }

        assertNever(restrictions, modification { it.embedded.value1 assign "TX" })
        assertNever(restrictions, modification { it.embedded.value2 assign 0 })
        assertNever(restrictions, modification { it.embedded assign ClassUsedForEmbedding("TX", 1) })
        // The part a write leaves alone must already hold, so a write can fix one part of a row that breaks the rule.
        assertEquals(condition { it.embedded.value2 neq 0 }, restrictions(modification { it.embedded.value1 assign "CO" }))
        assertEquals(condition { it.embedded.value1 eq "CO" }, restrictions(modification { it.embedded.value2 assign 5 }))
        assertEquals(Condition.Always, restrictions(modification { it.embedded assign ClassUsedForEmbedding("CO", 1) }))
    }

    @Test
    fun `mustBe with Or on a parent refuses child writes it cannot prove`() {
        val restrictions = updateRestrictions<LargeTestModel> {
            it.embedded.mustBe { (it.value1 eq "CO") or (it.value2 eq 0) }
        }

        assertNever(restrictions, modification { it.embedded.value1 assign "TX" })
        assertNever(restrictions, modification { it.embedded.value1 assign "CO" })
        assertEquals(Condition.Always, restrictions(modification { it.embedded assign ClassUsedForEmbedding("TX", 0) }))
    }

    @Test
    fun `mustBe on a nullable object checks writes inside it`() {
        val restrictions = updateRestrictions<LargeTestModel> {
            it.embeddedNullable.notNull.mustBe { it.value1 eq "CO" }
        }

        assertNever(restrictions, modification { it.embeddedNullable.notNull.value1 assign "TX" })
        assertNever(restrictions, modification { it.embeddedNullable.notNull.value1 += "X" })
        assertEquals(condition { it.embeddedNullable neq null }, restrictions(modification { it.embeddedNullable.notNull.value1 assign "CO" }))
        assertEquals(condition { it.embeddedNullable.notNull.value1 eq "CO" }, restrictions(modification { it.embeddedNullable.notNull.value2 assign 3 }))
    }

    @Test
    fun `guaranteedAfter fails closed on shapes it cannot prove`() {
        val embeddedValue1 = modification<LargeTestModel> { it.embedded.value1 assign "x" }
        assertEquals(false, Condition.Never.guaranteedAfter(embeddedValue1))
        assertEquals(false, Condition.Not(condition<LargeTestModel> { it.embedded.value1 neq "x" }).guaranteedAfter(embeddedValue1))
        assertEquals(true, condition<LargeTestModel> { it.embedded.value1 eq "x" }.guaranteedAfter(embeddedValue1))
        assertEquals(true, condition<LargeTestModel> { it.embedded.value2 eq 1 }.guaranteedAfter(embeddedValue1))
    }

    @Test
    fun `neq null on an object survives writes inside it but not assigning null`() {
        val restrictions = updateRestrictions<LargeTestModel> { it.embeddedNullable.mustBe { it neq null } }

        val nonNull = condition<LargeTestModel> { it.embeddedNullable neq null }
        assertEquals(nonNull, restrictions(modification { it.embeddedNullable.notNull.value1 assign "x" }))
        assertEquals(nonNull, restrictions(modification { it.embeddedNullable.notNull assign ClassUsedForEmbedding() }))
        assertEquals(Condition.Always, restrictions(modification { it.embeddedNullable assign ClassUsedForEmbedding() }))
        assertNever(restrictions, modification { it.embeddedNullable assign null })
    }

    @Test
    fun `eq null on an object still refuses writes inside it`() {
        val restrictions = updateRestrictions<LargeTestModel> { it.embeddedNullable.mustBe { it eq null } }

        assertNever(restrictions, modification { it.embeddedNullable.notNull.value1 assign "x" })
        assertEquals(Condition.Always, restrictions(modification { it.embeddedNullable assign null }))
    }

    @Test
    fun `Or and Not rules allow writes to fields they do not read`() {
        val or = updateRestrictions<LargeTestModel> { it.embedded.mustBe { (it.value1 eq "CO") or (it.value1 eq "TX") } }
        val not = updateRestrictions<LargeTestModel> { it.embedded.mustBe { !(it.value1 eq "x") } }

        assertEquals(or.fields.single().limitedTo, or(modification { it.embedded.value2 assign 5 }))
        assertNever(or, modification { it.embedded.value1 assign "CO" })
        assertEquals(not.fields.single().limitedTo, not(modification { it.embedded.value2 assign 5 }))
        assertNever(not, modification { it.embedded.value1 assign "y" })
    }

    @Test
    fun `a chain is checked last write first`() {
        val restrictions = updateRestrictions<LargeTestModel> {
            it.embedded.mustBe { (it.value1 eq "CO") and (it.value2 neq 0) }
        }

        assertEquals(Condition.Always, restrictions(modification {
            it.embedded.value1 assign "CO"
            it.embedded.value2 assign 5
        }))
        assertNever(restrictions, modification {
            it.embedded.value1 assign "CO"
            it.embedded.value1 assign "TX"
        })
        assertEquals(condition { it.embedded.value2 neq 0 }, restrictions(modification {
            it.embedded.value1 assign "TX"
            it.embedded.value1 assign "CO"
        }))
    }

    @Test
    fun `forEachIf keeps skipped elements under the rule`() {
        val restrictions = updateRestrictions<LargeTestModel> { it.listEmbedded.mustBe { it.all { it.value2 gt 2 } } }

        assertEquals(Condition.Always, restrictions(modification { it.listEmbedded.forEach { it.value2 assign 3 } }))
        assertEquals(
            restrictions.fields.single().limitedTo,
            restrictions(modification { it.listEmbedded.forEachIf({ it.value1 eq "x" }) { it.value2 assign 3 } })
        )
        assertNever(restrictions, modification { it.listEmbedded.forEach { it.value2 assign 1 } })
    }
}
