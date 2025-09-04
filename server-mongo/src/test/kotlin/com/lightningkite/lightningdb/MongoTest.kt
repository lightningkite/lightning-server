
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
    init {

        prepareModelsShared()
        prepareModelsServerCore()
        prepareModelsServerMongoTest()
        prepareModelsServerTesting()
    }
    val defaultMongo: MongoDatabase = TestDatabase.mongoClient
    val db get() = defaultMongo
}