@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningdb

import com.lightningkite.lightningdb.test.EmbeddedMap
import com.lightningkite.lightningdb.test.RecursiveEmbed
import com.mongodb.MongoWriteException
import com.mongodb.client.model.changestream.UpdateDescription
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.fail


class UniqueTest : MongoTest() {

    @Test
    fun test() {
        runBlocking {
            val collection =
                (defaultMongo.collection<IndexingTestModel>("UniqueTest_test") as MongoFieldCollection<IndexingTestModel>).mongo
            collection.insertOne(IndexingTestModel(email = "test@test.com", account = "asdf"))
            collection.insertOne(IndexingTestModel(email = "test@test.com", account = "fdsa"))
            collection.insertOne(IndexingTestModel(email = "test@test.com", account = null))
            collection.insertOne(IndexingTestModel(email = "test@test.com", account = null))
            collection.insertOne(IndexingTestModel(email = null, account = "asdf"))
            collection.insertOne(IndexingTestModel(email = null, account = "asdf"))
            try {
                collection.insertOne(IndexingTestModel(email = "test@test.com", account = "asdf"))
                fail()
            } catch(w: MongoWriteException) {
                /*expected*/
            }
        }
    }

}

@DatabaseModel
@Serializable
@UniqueSet(["email", "account"])
data class IndexingTestModel(
    override val _id: UUID = UUID.randomUUID(),
    val email: String? = null,
    val account: String? = null,
) : HasId<UUID>