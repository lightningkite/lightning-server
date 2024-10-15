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
        var condition = path<ModelWithTextIndex>().fullTextSearch(query, requireAllTermsPresent = false)
        var results = collection.find(condition).toList()
        assertContains(results, value1)

        query = "one"
        condition = path<ModelWithTextIndex>().fullTextSearch(query, requireAllTermsPresent = true)
        results = collection.find(condition).toList()
        assertContains(results, value1)

        query = "four two"
        condition = path<ModelWithTextIndex>().fullTextSearch(query, requireAllTermsPresent = true)
        results = collection.find(condition).toList()
        assertContains(results, value1)

        query = "four five"
        condition = path<ModelWithTextIndex>().fullTextSearch(query, requireAllTermsPresent = true)
        results = collection.find(condition).toList()
        assertContains(results, value2)

        query = "three five"
        condition = path<ModelWithTextIndex>().fullTextSearch(query, requireAllTermsPresent = true)
        results = collection.find(condition).toList()
        assertContains(results, value1)
        assertContains(results, value2)
    }
}