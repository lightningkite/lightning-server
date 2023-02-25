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
        (path<LargeTestModel>().int).let { modification ->
            assertTrue(modification<LargeTestModel>{it.int assign 3}.affects(modification))
            assertTrue(modification<LargeTestModel>{it.int += 3}.affects(modification))
            assertFalse(modification<LargeTestModel>{it.short assign 3}.affects(modification))
        }
        (path<LargeTestModel>().intNullable).let { modification ->
            assertTrue(modification<LargeTestModel>{it.intNullable assign 3}.affects(modification))
            assertTrue(modification<LargeTestModel>() { it.intNullable.notNull += 3 }.affects(modification))
            assertFalse(modification<LargeTestModel>{it.short assign 3}.affects(modification))
        }
    }

    @Test
    fun testConditionModification() {
        modification<LargeTestModel>() {it.int assign 2 }.let { modification ->
            assertTrue((path<LargeTestModel>().int eq 3).readsResultOf(modification))
            assertTrue((path<LargeTestModel>().int gt 3).readsResultOf(modification))
            assertFalse((path<LargeTestModel>().short eq 3).readsResultOf(modification))
            assertFalse((path<LargeTestModel>().always).readsResultOf(modification))
        }
        modification<LargeTestModel>() {it.intNullable assign 2 }.let { modification ->
            assertTrue((path<LargeTestModel>().intNullable eq 3).readsResultOf(modification))
            assertTrue((path<LargeTestModel>().intNullable.notNull gt 3).readsResultOf(modification))
            assertFalse((path<LargeTestModel>().short eq 3).readsResultOf(modification))
            assertFalse((path<LargeTestModel>().always).readsResultOf(modification))
        }
    }

    @Test
    fun testConditionKProperty1() {
        (path<LargeTestModel>().int).let { modification ->
            assertTrue((path<LargeTestModel>().int eq 3).reads(modification))
            assertTrue((path<LargeTestModel>().int gt 3).reads(modification))
            assertFalse((path<LargeTestModel>().short eq 3).reads(modification))
        }
        (path<LargeTestModel>().intNullable).let { modification ->
            assertTrue((path<LargeTestModel>().intNullable eq 3).reads(modification))
            assertTrue((path<LargeTestModel>().intNullable.notNull gt 3).reads(modification))
            assertFalse((path<LargeTestModel>().short eq 3).reads(modification))
        }
    }

    @Test
    fun testConditionModificationPasses() {
        (path<LargeTestModel>().int gt 2).let { condition ->
            assertFalse(condition.readsResultOf(modification { it.int assign 2 }))
            assertTrue(condition.readsResultOf(modification { it.int assign 3 }))
            assertFalse(condition.readsResultOf(modification { it.int += 1 }))
            assertTrue(condition.readsResultOf(modification { it.short += 1 }))
        }
        (path<LargeTestModel>().intNullable.notNull gt 2).let { condition ->
            assertFalse(condition.readsResultOf(modification { it.intNullable assign 2 }))
            assertFalse(condition.readsResultOf(modification { it.intNullable assign null }))
            assertTrue(condition.readsResultOf(modification { it.intNullable assign 3 }))
            assertFalse(condition.readsResultOf(modification { it.intNullable.notNull assign 2 }))
            assertTrue(condition.readsResultOf(modification { it.intNullable.notNull assign 3 }))
            assertFalse(condition.readsResultOf(modification { it.intNullable.notNull plusAssign 1 }))
        }
//        (startChain<LargeTestModel>().listEmbedded.all.value2 gt 2).let { condition ->
//            assertFalse(condition.invoke(modification<LargeTestModel> {it.listEmbedded.map { it.value2 assign 2 } }))
//            assertTrue(condition.invoke(modification<LargeTestModel> {it.listEmbedded.map { it.value2 assign 3 } }))
//            assertFalse(condition.invoke(modification<LargeTestModel> {it.listEmbedded.map { it.value2.plusAssign(1) } }))
//        }
    }
}