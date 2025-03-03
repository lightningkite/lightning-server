package com.lightningkite.lightningdb

import com.lightningkite.lightningdb.testing.ClassUsedForEmbedding
import com.lightningkite.lightningdb.testing.LargeTestModel
import com.lightningkite.lightningdb.testing.embeddedNullable
import com.lightningkite.lightningdb.testing.value1
import com.lightningkite.lightningdb.testing.value2
import com.lightningkite.prepareModelsShared
import com.lightningkite.prepareModelsSharedTest
import com.lightningkite.serialization.notNull
import com.lightningkite.serialization.partialOf
import kotlin.test.Test

class ModificationBuilderTests {
    init {
        prepareModelsShared()
        prepareModelsSharedTest()
    }
    @Test fun test() {
        val item = LargeTestModel(embeddedNullable = null)
        val partial = partialOf<LargeTestModel> {
            it.embeddedNullable.notNull.value1 assign "sample"
            it.embeddedNullable.notNull.value2 assign 123
        }
        val p = partial.toModification(LargeTestModel.serializer())
        println(p)
        println(p(item))
    }

    @Test fun test2() {
        val partial = partialOf<ClassUsedForEmbedding> {
            it.value1 assign "sample"
            it.value2 assign 123
        }
        println(partial.total(ClassUsedForEmbedding.serializer()))
    }
}