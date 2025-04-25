package com.lightningkite.lightningserver.db.test

import com.lightningkite.lightningserver.db.Modification
import com.lightningkite.lightningserver.db.modification
import com.lightningkite.lightningserver.db.simplify
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.prepareModelsServerCore
import com.lightningkite.prepareModelsShared
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.fail

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
            modification {
                it.int assign 1
                it.int plusAssign 1
            },
            modification {
                it.int plusAssign 1
                it.int assign 1
            },
            modification {
                it.int plusAssign 1
                it.int assign 1
                it.int timesAssign 2
            },
            modification {
                it.int assign 1
                it.int plusAssign 1
                it.int timesAssign 2
            },
            modification {
                it.int assign 1
                it.int plusAssign 1
                it.int timesAssign 2
                it.long assign 3
                it.long plusAssign 2
            },
            modification {
                it.int assign 1
                it.int plusAssign 1
                it.int timesAssign 2
                it.long plusAssign 2
            },
            modification {
                add(modification {
                    it.int assign 4
                    it.int plusAssign 4
                })
                add(modification {
                    it.int timesAssign 4
                    it.long plusAssign 4
                })
            },
            modification<LargeTestModel> {
                it.embedded.value1 assign "test"
                it.embedded assign ClassUsedForEmbedding("test2", 1)
            }
        ).forEach {
            val simplified = it.simplify()
            println("$it --> $simplified")
            assertEquals(it(item), simplified(item))
            fun Modification<*>.assertNoNothings() {
                when(this) {
                    is Modification.Nothing -> fail()
                    is Modification.Chain -> modifications.forEach { it.assertNoNothings() }
                    is Modification.SetPerElement<*> -> modification.assertNoNothings()
                    is Modification.ListPerElement<*> -> modification.assertNoNothings()
                    is Modification.OnField<*, *> -> modification.assertNoNothings()
                    else -> {}
                }
            }
            simplified.assertNoNothings()
        }
    }

    @Test fun annoyingNested() {
        modification<LargeTestModel> {
            it.embedded.value1 assign "test"
            it.embedded assign ClassUsedForEmbedding("test2", 1)
        }.also { println(it) }.simplify().also { println(it) }
    }
}
