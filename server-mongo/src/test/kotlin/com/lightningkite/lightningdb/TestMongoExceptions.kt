package com.lightningkite.lightningdb

import com.lightningkite.prepareModelsServerCore
import com.lightningkite.lightningdb.test.prepareModelsServerTesting
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.prepareModelsShared
import com.mongodb.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertFailsWith

class TestMongoExceptions : MongoTest() {

    @Test
    fun testUniqueError(): Unit = runBlocking {

        prepareModelsShared()
        prepareModelsServerCore()
        prepareModelsServerTesting()
        prepareModelsServerMongoTest()

        val collection =
            (db.collection<IndexingTestModel>("MongoExceptions_unique") as MongoFieldCollection<IndexingTestModel>)

        collection.insertOne(IndexingTestModel(email = "test@test.com", account = "asdf"))

        assertFailsWith<UniqueViolationException> { collection.insertOne(IndexingTestModel(email = "test@test.com", account = "asdf"))!! }
        assertFailsWith<UniqueViolationException> {
            val item = collection.insertOne(IndexingTestModel(email = "test1@test.com", account = "asdf"))!!
            collection.updateOneById(item._id, modification { it.email assign "test@test.com" })
        }

    }

}