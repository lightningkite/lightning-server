@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningdb

import com.lightningkite.lightningdb.test.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.junit.Test
import java.util.*
import com.lightningkite.UUID
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.lightningkite.uuid

@Serializable
@GenerateDataClassPaths
@TextIndex(["string"])
data class ModelWithTextIndex(
    override val _id: UUID = uuid(),
    val string: String
): HasId<UUID>

class TextConditionTest: MongoTest() {

    @Test
    fun testTextSearch() = runBlocking {
        val collection = defaultMongo.collection<ModelWithTextIndex>() as MongoFieldCollection<ModelWithTextIndex>
        val value1 = ModelWithTextIndex(string = "One Two Three")
        val value2 = ModelWithTextIndex(string = "Five Six Seven")
        collection.insertOne(value1)
        collection.insertOne(value2)

        var query = "One"
        var condition = path<ModelWithTextIndex>().fullTextSearch(query, false)
        var results = collection.find(condition).toList()
        assertContains(results, value1)

        query = "one"
        condition = path<ModelWithTextIndex>().fullTextSearch(query, false)
        results = collection.find(condition).toList()
        assertFalse(results.contains(value1))

        query = "one"
        condition = path<ModelWithTextIndex>().fullTextSearch(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value1)

        query = "four two"
        condition = path<ModelWithTextIndex>().fullTextSearch(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value1)

        query = "four five"
        condition = path<ModelWithTextIndex>().fullTextSearch(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value2)

        query = "three five"
        condition = path<ModelWithTextIndex>().fullTextSearch(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value1)
        assertContains(results, value2)
    }

    @Test
    fun testContains() = runBlocking {
        val collection = defaultMongo.collection<ModelWithTextIndex>("TextConditionSearchTest") as MongoFieldCollection<ModelWithTextIndex>
        val value1 = ModelWithTextIndex(string = "One Two Three")
        val value2 = ModelWithTextIndex(string = "Five Six Seven")
        collection.insertOne(value1)
        collection.insertOne(value2)

        var query = "One"
        var condition = path<ModelWithTextIndex>().string.contains(query, false)
        var results = collection.find(condition).toList()
        assertContains(results, value1)

        query = "one"
        condition = path<ModelWithTextIndex>().string.contains(query, false)
        results = collection.find(condition).toList()
        assertFalse(results.contains(value1))

        query = "one"
        condition = path<ModelWithTextIndex>().string.contains(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value1)

        query = "four two"
        condition = path<ModelWithTextIndex>().string.contains(query, true)
        results = collection.find(condition).toList()
        assertTrue(results.isEmpty())

        query = "six seven"
        condition = path<ModelWithTextIndex>().string.contains(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value2)

        query = "seven six"
        condition = path<ModelWithTextIndex>().string.contains(query, true)
        results = collection.find(condition).toList()
        assertTrue(results.isEmpty())

        query = "four five"
        condition = path<ModelWithTextIndex>().string.contains(query, true)
        results = collection.find(condition).toList()
        assertTrue(results.isEmpty())

        query = "three five"
        condition = path<ModelWithTextIndex>().string.contains(query, true)
        results = collection.find(condition).toList()
        assertTrue(results.isEmpty())
    }

}