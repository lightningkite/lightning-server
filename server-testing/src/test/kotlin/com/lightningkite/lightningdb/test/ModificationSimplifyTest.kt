package com.lightningkite.lightningdb.test

import com.lightningkite.lightningdb.Modification
import com.lightningkite.lightningdb.modification
import com.lightningkite.lightningdb.simplify
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.prepareModelsServerCore
import com.lightningkite.prepareModelsShared
import org.junit.Test
import kotlin.test.assertEquals

class ModificationSimplifyTest {
    init {
        TestSettings
    }
    @Test
    fun test() {
        prepareModelsShared()
        prepareModelsServerCore()
        prepareModelsServerTesting()
        val item = LargeTestModel()
        val mods = listOf(
            modification<LargeTestModel> { it.int assign 1 } to modification<LargeTestModel> { it.int assign 1 },
            modification<LargeTestModel> {
                it assign item
                it.int assign 1
            } to modification<LargeTestModel> { it assign item.copy(int = 1) },
        )
        for((pre, post) in mods) {
            assertEquals(post, pre.simplify().also { println("$pre -> $it") })
            assertEquals(pre(item), post(item))
        }
        listOf<Modification<LargeTestModel>>(
            modification {
                it assign item
                it.int assign 1
            },
            modification {
                it.int assign 1
                it assign item
            },
            modification {
                it assign item
                it.int plusAssign 1
            },
            modification {
                it.int plusAssign 1
                it assign item
            },
            modification {
                it.int assign 1
            },
        ).forEach {
            assertEquals(it(item), it.simplify()(item))
        }
    }
}
