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
            assertTrue((startChain<LargeTestModel>().int assign 3).matchesPath(modification))
            assertTrue((startChain<LargeTestModel>().int plus 3).matchesPath(modification))
            assertFalse((startChain<LargeTestModel>().short assign 3).matchesPath(modification))
        }
        (LargeTestModel::intNullable).let { modification ->
            assertTrue((startChain<LargeTestModel>().intNullable assign 3).matchesPath(modification))
            assertTrue((startChain<LargeTestModel>().intNullable.notNull plus 3).matchesPath(modification))
            assertFalse((startChain<LargeTestModel>().short assign 3).matchesPath(modification))
        }
    }

    @Test
    fun testModificationModification() {
        (startChain<LargeTestModel>().int assign 2).let { modification ->
            assertTrue((startChain<LargeTestModel>().int assign 3).matchesPath(modification))
            assertTrue((startChain<LargeTestModel>().int plus 3).matchesPath(modification))
            assertFalse((startChain<LargeTestModel>().short assign 3).matchesPath(modification))
        }
        (startChain<LargeTestModel>().intNullable assign 2).let { modification ->
            assertTrue((startChain<LargeTestModel>().intNullable assign 3).matchesPath(modification))
            assertTrue((startChain<LargeTestModel>().intNullable.notNull plus 3).matchesPath(modification))
            assertFalse((startChain<LargeTestModel>().short assign 3).matchesPath(modification))
        }
        (startChain<LargeTestModel>().listEmbedded.all.value2 assign 2).let { modification ->
            assertTrue((startChain<LargeTestModel>().listEmbedded.map { it.value2.plus(1) }).matchesPath(modification))
            assertFalse((startChain<LargeTestModel>().listEmbedded.map { it.value1 assign "" }).matchesPath(modification))
        }
    }

    @Test
    fun testConditionModification() {
        (startChain<LargeTestModel>().int assign 2).let { modification ->
            assertTrue((startChain<LargeTestModel>().int eq 3).matchesPath(modification))
            assertTrue((startChain<LargeTestModel>().int gt 3).matchesPath(modification))
            assertFalse((startChain<LargeTestModel>().short eq 3).matchesPath(modification))
            assertFalse((startChain<LargeTestModel>().always).matchesPath(modification))
        }
        (startChain<LargeTestModel>().intNullable assign 2).let { modification ->
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
            assertFalse(condition.invoke(modification { it.int plus 1 }))
            assertTrue(condition.invoke(modification { it.short plus 1 }))
        }
        (startChain<LargeTestModel>().intNullable.notNull gt 2).let { condition ->
            assertFalse(condition.invoke(modification { it.intNullable assign 2 }))
            assertFalse(condition.invoke(modification { it.intNullable assign null }))
            assertTrue(condition.invoke(modification { it.intNullable assign 3 }))
            assertFalse(condition.invoke(modification { it.intNullable.notNull assign 2 }))
            assertTrue(condition.invoke(modification { it.intNullable.notNull assign 3 }))
            assertFalse(condition.invoke(modification { it.intNullable.notNull plus 1 }))
        }
        (startChain<LargeTestModel>().listEmbedded.all.value2 gt 2).let { condition ->
            assertFalse(condition.invoke(startChain<LargeTestModel>().listEmbedded.map { it.value2 assign 2 }))
            assertTrue(condition.invoke(startChain<LargeTestModel>().listEmbedded.map { it.value2 assign 3 }))
            assertFalse(condition.invoke(startChain<LargeTestModel>().listEmbedded.map { it.value2.plus(1) }))
        }
    }
}