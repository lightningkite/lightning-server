package com.lightningkite.lightningdb.test

import com.lightningkite.lightningdb.Modification
import com.lightningkite.lightningdb.modification
import com.lightningkite.lightningdb.simplify
import org.junit.Test
import kotlin.test.assertEquals

class ModificationSimplifyTest {
    init {
        TestSettings
    }
    @Test
    fun test() {
        prepareModels()
        val item = LargeTestModel()
        val mods = listOf<Pair<Modification<LargeTestModel>, Modification<LargeTestModel>>>(
            modification { it.int assign 1 } to modification { it.int assign 1 },
            modification {
                it assign item
                it.int assign 1
            } to modification { it assign item.copy(int = 1) },
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
