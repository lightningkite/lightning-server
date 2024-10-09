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

class MongoAggregationsTest : AggregationsTest() {

    companion object {
        var mongoClient: MongoClient? = null
        lateinit var defaultMongo: MongoDatabase

        @BeforeClass
        @JvmStatic
        fun start() {
            mongoClient = testMongo()
            defaultMongo = MongoDatabase("default") { mongoClient!! }
            prepareModelsShared()
            prepareModelsServerCore()
            prepareModelsServerTesting()
            prepareModelsServerMongoTest()
        }

        @AfterClass
        @JvmStatic
        fun after() {
            mongoClient?.close()
        }
    }

    override val database: Database = Companion.defaultMongo
}

class MongoConditionTests : ConditionTests() {

    companion object {
        var mongoClient: MongoClient? = null
        lateinit var defaultMongo: MongoDatabase

        init {
            prepareModelsShared()
            prepareModelsServerCore()
            prepareModelsServerMongoTest()
            prepareModelsServerTesting()
        }

        @BeforeClass
        @JvmStatic
        fun start() {
            mongoClient = testMongo()
            defaultMongo = MongoDatabase("default") { mongoClient!! }
        }

        @AfterClass
        @JvmStatic
        fun after() {
            mongoClient?.close()
        }
    }

    override val database: Database = Companion.defaultMongo

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

    companion object {
        var mongoClient: MongoClient? = null
        lateinit var defaultMongo: MongoDatabase

        @BeforeClass
        @JvmStatic
        fun start() {
            mongoClient = testMongo()
            defaultMongo = MongoDatabase("default") { mongoClient!! }
            prepareModelsShared()
            prepareModelsServerCore()
            prepareModelsServerMongoTest()
            prepareModelsServerTesting()
        }

        @AfterClass
        @JvmStatic
        fun after() {
            mongoClient?.close()
        }
    }

    override val database: Database = Companion.defaultMongo
}

class MongoOperationsTests : OperationsTests() {

    companion object {
        var mongoClient: MongoClient? = null
        lateinit var defaultMongo: MongoDatabase

        @BeforeClass
        @JvmStatic
        fun start() {
            mongoClient = testMongo()
            defaultMongo = MongoDatabase("default") { mongoClient!! }
            prepareModelsShared()
            prepareModelsServerCore()
            prepareModelsServerMongoTest()
            prepareModelsServerTesting()
        }

        @AfterClass
        @JvmStatic
        fun after() {
            mongoClient?.close()
        }
    }

    override val database: Database = Companion.defaultMongo
}

class MongoSortTest : SortTest() {

    companion object {
        var mongoClient: MongoClient? = null
        lateinit var defaultMongo: MongoDatabase

        @BeforeClass
        @JvmStatic
        fun start() {
            mongoClient = testMongo()
            defaultMongo = MongoDatabase("default") { mongoClient!! }
            prepareModelsShared()
            prepareModelsServerCore()
            prepareModelsServerMongoTest()
            prepareModelsServerTesting()
        }

        @AfterClass
        @JvmStatic
        fun after() {
            mongoClient?.close()
        }
    }

    override val database: Database = Companion.defaultMongo
}

class MongoMetaTest : MetaTest() {

    companion object {
        var mongoClient: MongoClient? = null
        lateinit var defaultMongo: MongoDatabase

        @BeforeClass
        @JvmStatic
        fun start() {
            mongoClient = testMongo()
            defaultMongo = MongoDatabase("default") { mongoClient!! }
            prepareModelsShared()
            prepareModelsServerCore()
            prepareModelsServerMongoTest()
            prepareModelsServerTesting()
        }

        @AfterClass
        @JvmStatic
        fun after() {
            mongoClient?.close()
        }
    }

    override val database: Database = Companion.defaultMongo
}