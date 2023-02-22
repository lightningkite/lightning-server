package com.lightningkite.lightningdb.test

import com.lightningkite.lightningdb.*
import org.junit.Assert.*
import org.junit.Test

class PathsKtTest {
    init {
        prepareModels()
    }

    @Test
    fun testModificationKProperty1() {
        (LargeTestModel::int).let { modification ->
            assertTrue(modification<LargeTestModel>{it.int assign 3}.matchesPath(modification))
            assertTrue(modification<LargeTestModel>{it.int += 3}.matchesPath(modification))
            assertFalse(modification<LargeTestModel>{it.short assign 3}.matchesPath(modification))
        }
        (LargeTestModel::intNullable).let { modification ->
            assertTrue(modification<LargeTestModel>{it.intNullable assign 3}.matchesPath(modification))
            assertTrue(modification<LargeTestModel>() { it.intNullable.notNull += 3 }.matchesPath(modification))
            assertFalse(modification<LargeTestModel>{it.short assign 3}.matchesPath(modification))
        }
    }

    @Test
    fun testModificationModification() {
        (modification<LargeTestModel> { it.int assign 2 } ).let { modification ->
            assertTrue((modification<LargeTestModel> { it.int assign 3 } ).matchesPath(modification))
            assertTrue((modification<LargeTestModel> { it.int += 3 } ).matchesPath(modification))
            assertFalse((modification<LargeTestModel> { it.short assign 3 } ).matchesPath(modification))
        }
        (modification<LargeTestModel> { it.intNullable assign 2 } ).let { modification ->
            assertTrue((modification<LargeTestModel> { it.intNullable assign 3 } ).matchesPath(modification))
            assertTrue((modification<LargeTestModel> { it.intNullable.notNull += 3 }).matchesPath(modification))
            assertFalse((modification<LargeTestModel> { it.short assign 3 } ).matchesPath(modification))
        }
        (startChain<LargeTestModel>().listEmbedded.all.value2 assign 2).let { modification ->
            assertTrue((modification<LargeTestModel> { it.listEmbedded.map { it.value2.plus(1) }}).matchesPath(modification))
            assertFalse((modification<LargeTestModel> { it.listEmbedded.map { it.value1 assign "" }}).matchesPath(modification))
        }
    }

    @Test
    fun testConditionModification() {
        modification<LargeTestModel>() {it.int assign 2 }.let { modification ->
            assertTrue((startChain<LargeTestModel>().int eq 3).matchesPath(modification))
            assertTrue((startChain<LargeTestModel>().int gt 3).matchesPath(modification))
            assertFalse((startChain<LargeTestModel>().short eq 3).matchesPath(modification))
            assertFalse((startChain<LargeTestModel>().always).matchesPath(modification))
        }
        modification<LargeTestModel>() {it.intNullable assign 2 }.let { modification ->
            assertTrue((startChain<LargeTestModel>().intNullable eq 3).matchesPath(modification))
            assertTrue((startChain<LargeTestModel>().intNullable.notNull gt 3).matchesPath(modification))
            assertFalse((startChain<LargeTestModel>().short eq 3).matchesPath(modification))
            assertFalse((startChain<LargeTestModel>().always).matchesPath(modification))
        }
    }

    @Test
    fun testConditionKProperty1() {
        (LargeTestModel::int).let { modification ->
            assertTrue((startChain<LargeTestModel>().int eq 3).matchesPath(modification))
            assertTrue((startChain<LargeTestModel>().int gt 3).matchesPath(modification))
            assertFalse((startChain<LargeTestModel>().short eq 3).matchesPath(modification))
        }
        (LargeTestModel::intNullable).let { modification ->
            assertTrue((startChain<LargeTestModel>().intNullable eq 3).matchesPath(modification))
            assertTrue((startChain<LargeTestModel>().intNullable.notNull gt 3).matchesPath(modification))
            assertFalse((startChain<LargeTestModel>().short eq 3).matchesPath(modification))
        }
    }

    @Test
    fun testConditionModificationPasses() {
        (startChain<LargeTestModel>().int gt 2).let { condition ->
            assertFalse(condition.invoke(modification { it.int assign 2 }))
            assertTrue(condition.invoke(modification { it.int assign 3 }))
            assertFalse(condition.invoke(modification { it.int += 1 }))
            assertTrue(condition.invoke(modification { it.short += 1 }))
        }
        (startChain<LargeTestModel>().intNullable.notNull gt 2).let { condition ->
            assertFalse(condition.invoke(modification { it.intNullable assign 2 }))
            assertFalse(condition.invoke(modification { it.intNullable assign null }))
            assertTrue(condition.invoke(modification { it.intNullable assign 3 }))
            assertFalse(condition.invoke(modification { it.intNullable.notNull assign 2 }))
            assertTrue(condition.invoke(modification { it.intNullable.notNull assign 3 }))
            assertFalse(condition.invoke(modification { it.intNullable.notNull plusAssign 1 }))
        }
        (startChain<LargeTestModel>().listEmbedded.all.value2 gt 2).let { condition ->
            assertFalse(condition.invoke(modification<LargeTestModel> {it.listEmbedded.map { it.value2 assign 2 } }))
            assertTrue(condition.invoke(modification<LargeTestModel> {it.listEmbedded.map { it.value2 assign 3 } }))
            assertFalse(condition.invoke(modification<LargeTestModel> {it.listEmbedded.map { it.value2.plusAssign(1) } }))
        }
    }
}