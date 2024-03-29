package com.lightningkite.lightningdb

import com.mongodb.kotlin.client.coroutine.MongoClient
import org.junit.AfterClass
import org.junit.BeforeClass


abstract class MongoTest {
    val defaultMongo: MongoDatabase get() = Companion.db
    companion object {
        var mongoClient: MongoClient? = null
        lateinit var db: MongoDatabase
        @BeforeClass @JvmStatic
        fun start() {
            mongoClient = testMongo()
            db = MongoDatabase("default") { mongoClient!! }
            com.lightningkite.lightningdb.test.prepareModels()
        }

        @AfterClass @JvmStatic
        fun after() {
            mongoClient?.close()
        }
    }
}