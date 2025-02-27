package com.lightningkite.lightningdb

import com.lightningkite.prepareModelsServerCore
import com.lightningkite.lightningdb.test.*
import com.lightningkite.prepareModelsShared
import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals

object TestDatabase {
    val settings = testMongo()
    val mongoClient = MongoDatabase("default", clientSettings = TestDatabase.settings)
}

class MongoAggregationsTest : AggregationsTest() {
    override val database: Database = TestDatabase.mongoClient
}

class MongoConditionTests : ConditionTests() {
    init {
        prepareModelsShared()
        prepareModelsServerCore()
        prepareModelsServerMongoTest()
        prepareModelsServerTesting()
    }
    override val database: Database = TestDatabase.mongoClient

    @Test
    fun testNot() = runBlocking {
        val collection = database.collection<LargeTestModel>("LargeTestModel_testNot")
        val match = LargeTestModel(int = 0)
        val notMatch = LargeTestModel(int = 1)
        val manualList = listOf(match, notMatch)
        collection.insertOne(match)
        collection.insertOne(notMatch)
        val condition = condition<LargeTestModel>() { !it.int.eq(1) }
        val results = collection.find(condition).toList()
        assertEquals(listOf(match), results)
        Unit
    }

    @Test
    fun testNot2() = runBlocking {
        val collection = database.collection<LargeTestModel>("LargeTestModel_testNot2")
        val match = LargeTestModel(int = 0)
        val notMatch = LargeTestModel(int = 1)
        val manualList = listOf(match, notMatch)
        collection.insertOne(match)
        collection.insertOne(notMatch)
        val condition = condition<LargeTestModel>() { it.int.condition { !it.eq(1) } }
        val results = collection.find(condition).toList()
        assertEquals(listOf(match), results)
        Unit
    }
}

class MongoModificationTests : ModificationTests() {
    init {

        prepareModelsShared()
        prepareModelsServerCore()
        prepareModelsServerMongoTest()
        prepareModelsServerTesting()
    }
    override val database: Database = TestDatabase.mongoClient
}

class MongoOperationsTests : OperationsTests() {
    init {

        prepareModelsShared()
        prepareModelsServerCore()
        prepareModelsServerMongoTest()
        prepareModelsServerTesting()
    }
    override val database: Database = TestDatabase.mongoClient
}

class MongoSortTest : SortTest() {
    init {

        prepareModelsShared()
        prepareModelsServerCore()
        prepareModelsServerMongoTest()
        prepareModelsServerTesting()
    }
    override val database: Database = TestDatabase.mongoClient
}

class MongoMetaTest : MetaTest() {
    init {

        prepareModelsShared()
        prepareModelsServerCore()
        prepareModelsServerMongoTest()
        prepareModelsServerTesting()
    }
    override val database: Database = TestDatabase.mongoClient
}