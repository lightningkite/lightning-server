@file:UseContextualSerialization(UUID::class)

package com.lightningkite.ktordb

import com.lightningkite.ktordb.application.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.junit.Test
import java.util.*
import kotlin.test.assertContains
import kotlin.test.assertEquals

@DatabaseModel
@Serializable
data class MetaTestModel(
    override val _id: UUID = UUID.randomUUID(),
    val condition: Condition<LargeTestModel>,
    val modification: Modification<LargeTestModel>
) : HasId<UUID> {
}

class MetaTest : MongoTest() {

    @Test
    fun test(): Unit = runBlocking {
        val c = defaultMongo.collection<MetaTestModel>()
        com.lightningkite.ktordb.application.prepareModels()
        prepareModels()
        val toInsert = MetaTestModel(
            condition = condition { it.int gt 3 },
            modification = modification { it.int + 2 }
        )
        c.insertOne(toInsert)
        val results = c.find(Condition.Always()).toList()
        results.forEach { println(it) }
        assertContains(results, toInsert)
    }
}