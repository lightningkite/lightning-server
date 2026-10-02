@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningdb

import com.lightningkite.UUID
import com.lightningkite.lightningdb.test.*
import com.lightningkite.serialization.*
import com.lightningkite.uuid
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.junit.Test
import kotlin.test.assertEquals

// Mongo-specific behaviour of notNull and modifyByKey checks; the shared ModificationTests cover what all backends agree on.
class NullCheckTest : MongoTest() {

    @Test
    fun notNull_missingField_staysMissing(): Unit = runBlocking {
        val old = defaultMongo.collection<NullCheckOldShape>("NullCheckTest_missingField")
        val new = defaultMongo.collection<NullCheckNewShape>("NullCheckTest_missingField")
        val row = NullCheckOldShape()
        old.insertOne(row)
        new.updateOneById(row._id, modification { it.embedded.notNull.value1 assign "x" })
        assertEquals(NullCheckNewShape(row._id, embedded = null), new.get(row._id))
    }

    // Mongo leaves the whole row unchanged when any check fails, where memory applies the parts whose checks hold.
    @Test
    fun notNull_failedCheck_leavesWholeRowUnchanged(): Unit = runBlocking {
        val collection = defaultMongo.collection<LargeTestModel>("NullCheckTest_failedCheck")
        val row = LargeTestModel(int = 0, intNullable = null)
        collection.insertOne(row)
        val change = collection.updateOneById(row._id, modification {
            it.int assign 1
            it.intNullable.notNull assign 5
        })
        assertEquals(EntryChange<LargeTestModel>(null, null), change)
        assertEquals(row, collection.get(row._id))
    }

    @Test
    fun notNull_upsertOnFailedCheck_doesNotInsert(): Unit = runBlocking {
        val collection = defaultMongo.collection<LargeTestModel>("NullCheckTest_upsert")
        val row = LargeTestModel(intNullable = null)
        collection.insertOne(row)
        val change = collection.upsertOne(
            condition { it._id eq row._id },
            modification { it.intNullable.notNull assign 5 },
            LargeTestModel(_id = row._id, intNullable = 5),
        )
        assertEquals(EntryChange(row, row), change)
        assertEquals(true, collection.upsertOneIgnoringResult(
            condition { it.int eq row.int },
            modification { it.intNullable.notNull assign 5 },
            LargeTestModel(intNullable = 5),
        ))
        assertEquals(listOf(row), collection.find(Condition.Always).toList())
    }

    // In memory, modifying a missing key throws; Mongo used to create the key.
    @Test
    fun modifyByKey_missingKey_unchanged(): Unit = runBlocking {
        val collection = defaultMongo.collection<LargeTestModel>("NullCheckTest_modifyByKey")
        val row = LargeTestModel(map = mapOf("a" to 1))
        collection.insertOne(row)
        collection.updateOneById(row._id, modification { it.map.modifyByKey(mapOf("b" to { it += 1 })) })
        assertEquals(row, collection.get(row._id))
    }
}

@GenerateDataClassPaths
@Serializable
data class NullCheckOldShape(
    override val _id: UUID = UUID.random(),
) : HasId<UUID>

@GenerateDataClassPaths
@Serializable
data class NullCheckNewShape(
    override val _id: UUID = UUID.random(),
    val embedded: ClassUsedForEmbedding? = null,
) : HasId<UUID>
