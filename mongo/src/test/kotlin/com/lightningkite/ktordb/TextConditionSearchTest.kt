package com.lightningkite.ktordb

import com.lightningkite.ktordb.application.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class TextConditionSearchTest: MongoTest() {

    @Test
    fun testTextSearch() = runBlocking {
        val collection = defaultMongo.collection<LargeTestModel>() as MongoFieldCollection<LargeTestModel>
        collection.wraps.createIndex("{string:\"text\"}")
        val value1 = LargeTestModel(string = "One Two Three")
        val value2 = LargeTestModel(string = "Five Six Seven")
        collection.insertOne(value1)
        collection.insertOne(value2)

        var query = "One"
        var condition = startChain<LargeTestModel>().string.contains(query, false)
        var results = collection.find(condition).toList()
        assertContains(results, value1)

        query = "one"
        condition = startChain<LargeTestModel>().string.contains(query, false)
        results = collection.find(condition).toList()
        assertFalse(results.contains(value1))

        query = "one"
        condition = startChain<LargeTestModel>().string.contains(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value1)

        query = "four two"
        condition = startChain<LargeTestModel>().string.contains(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value1)

        query = "four five"
        condition = startChain<LargeTestModel>().string.contains(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value2)

        query = "three five"
        condition = startChain<LargeTestModel>().string.contains(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value1)
        assertContains(results, value2)

    }
}