@file:UseContextualSerialization(UUID::class)

package com.lightningkite.lightningdb

import com.lightningkite.lightningdb.test.EmbeddedMap
import com.lightningkite.lightningdb.test.RecursiveEmbed
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.mongodb.MongoBulkWriteException
import com.mongodb.MongoWriteException
import com.mongodb.client.model.changestream.UpdateDescription
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.junit.Test
import java.util.*
import com.lightningkite.UUID
import kotlin.test.assertEquals
import kotlin.test.fail
import com.lightningkite.uuid
import kotlinx.coroutines.flow.forEach


class ReinterpretTest : MongoTest() {

    @Test
    fun test() {
        runBlocking {
            val collectionOld =
                (defaultMongo.collection<OldVersionModel>("ReinterpretTest_test") as MongoFieldCollection<OldVersionModel>)
            val collectionNew =
                (defaultMongo.collection<NewVersionModel>("ReinterpretTest_test") as MongoFieldCollection<NewVersionModel>)

            collectionOld.insertOne(OldVersionModel(number = 32))
            collectionNew.find(condition { it.number.eq(32) }).collect { println(it) }
        }
    }

}

@GenerateDataClassPaths
@Serializable
data class OldVersionModel(
    override val _id: UUID = UUID.random(),
    val number: Int,
) : HasId<UUID>
@GenerateDataClassPaths
@Serializable
data class NewVersionModel(
    override val _id: UUID = UUID.random(),
    val number: Long,
) : HasId<UUID>