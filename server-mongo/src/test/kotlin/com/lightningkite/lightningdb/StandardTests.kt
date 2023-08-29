package com.lightningkite.lightningdb

import com.lightningkite.lightningdb.test.*
import com.lightningkite.lightningserver.db.InMemoryDatabase
import com.mongodb.kotlin.client.coroutine.MongoClient
import org.junit.AfterClass
import org.junit.BeforeClass

class MongoAggregationsTest : AggregationsTest() {

    companion object {
        var mongoClient: MongoClient? = null
        lateinit var defaultMongo: MongoDatabase

        @BeforeClass
        @JvmStatic
        fun start() {
            mongoClient = testMongo()
            defaultMongo = MongoDatabase("default") { mongoClient!! }
            prepareModels()
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

        @BeforeClass
        @JvmStatic
        fun start() {
            mongoClient = testMongo()
            defaultMongo = MongoDatabase("default") { mongoClient!! }
            prepareModels()
        }

        @AfterClass
        @JvmStatic
        fun after() {
            mongoClient?.close()
        }
    }

    override val database: Database = Companion.defaultMongo
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
            prepareModels()
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
            prepareModels()
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
            prepareModels()
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
            prepareModels()
        }

        @AfterClass
        @JvmStatic
        fun after() {
            mongoClient?.close()
        }
    }

    override val database: Database = Companion.defaultMongo
}