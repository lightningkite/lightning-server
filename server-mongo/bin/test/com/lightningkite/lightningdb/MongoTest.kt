package com.lightningkite.lightningdb

import com.mongodb.reactivestreams.client.MongoClient
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
            db = mongoClient!!.database("default")
            com.lightningkite.lightningdb.test.prepareModels()
        }

        @AfterClass @JvmStatic
        fun after() {
            mongoClient?.close()
        }
    }
}