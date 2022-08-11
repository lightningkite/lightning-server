package com.lightningkite.lightningdb

import com.lightningkite.lightningdb.test.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextConditionTest: MongoTest() {

    @Test
    fun testTextSearch() = runBlocking {
        val collection = defaultMongo.collection<LargeTestModel>() as MongoFieldCollection<LargeTestModel>
        val value1 = LargeTestModel(string = "One Two Three")
        val value2 = LargeTestModel(string = "Five Six Seven")
        collection.insertOne(value1)
        collection.insertOne(value2)

        var query = "One"
        var condition = startChain<LargeTestModel>().fullTextSearch(query, false)
        var results = collection.find(condition).toList()
        assertContains(results, value1)

        query = "one"
        condition = startChain<LargeTestModel>().fullTextSearch(query, false)
        results = collection.find(condition).toList()
        assertFalse(results.contains(value1))

        query = "one"
        condition = startChain<LargeTestModel>().fullTextSearch(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value1)

        query = "four two"
        condition = startChain<LargeTestModel>().fullTextSearch(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value1)

        query = "four five"
        condition = startChain<LargeTestModel>().fullTextSearch(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value2)

        query = "three five"
        condition = startChain<LargeTestModel>().fullTextSearch(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value1)
        assertContains(results, value2)
    }

    @Test
    fun testContains() = runBlocking {
        val collection = defaultMongo.collection<LargeTestModel>("TextConditionSearchTest") as MongoFieldCollection<LargeTestModel>
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
        assertTrue(results.isEmpty())

        query = "six seven"
        condition = startChain<LargeTestModel>().string.contains(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value2)

        query = "seven six"
        condition = startChain<LargeTestModel>().string.contains(query, true)
        results = collection.find(condition).toList()
        assertTrue(results.isEmpty())

        query = "four five"
        condition = startChain<LargeTestModel>().string.contains(query, true)
        results = collection.find(condition).toList()
        assertTrue(results.isEmpty())

        query = "three five"
        condition = startChain<LargeTestModel>().string.contains(query, true)
        results = collection.find(condition).toList()
        assertTrue(results.isEmpty())
    }

}