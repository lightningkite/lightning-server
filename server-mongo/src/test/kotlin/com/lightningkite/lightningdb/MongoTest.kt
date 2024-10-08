@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningdb

import com.lightningkite.UUID
import com.lightningkite.prepareModelsServerCore
import com.lightningkite.lightningdb.test.prepareModelsServerTesting
import com.lightningkite.prepareModelsShared
import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.serialization.UseContextualSerialization
import org.junit.AfterClass
import org.junit.BeforeClass


abstract class MongoTest {
    val defaultMongo: MongoDatabase get() = Companion.db

    companion object {
        var mongoClient: MongoClient? = null
        lateinit var db: MongoDatabase

        @BeforeClass
        @JvmStatic
        fun start() {
            mongoClient = testMongo()
            db = MongoDatabase("default") { mongoClient!! }
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
}