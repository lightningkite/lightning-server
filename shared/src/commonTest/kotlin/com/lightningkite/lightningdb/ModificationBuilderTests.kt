package com.lightningkite.lightningserver.db

import com.lightningkite.lightningserver.db.testing.ClassUsedForEmbedding
import com.lightningkite.lightningserver.db.testing.LargeTestModel
import com.lightningkite.lightningserver.db.testing.embeddedNullable
import com.lightningkite.lightningserver.db.testing.value1
import com.lightningkite.lightningserver.db.testing.value2
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