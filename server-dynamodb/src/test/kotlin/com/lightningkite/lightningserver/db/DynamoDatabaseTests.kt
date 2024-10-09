@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
import com.lightningkite.lightningdb.Condition
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import java.net.URI
import com.lightningkite.UUID
import com.lightningkite.prepareModelsServerCore
import com.lightningkite.prepareModelsShared
import com.lightningkite.uuid

class DynamoDBTests() {
    @Test
    fun test() {
        prepareModelsShared()
        prepareModelsServerCore()
        prepareModelsServerDynamodbTest()
        runBlocking {
            val database = DynamoDatabase(embeddedDynamo())
            println("Defining table")
            val collection = database.collection<TestData>()
            val special = TestData(value = 0)
            collection.insert(listOf(
                special,
                TestData(value = 1),
                TestData(value = 2),
                TestData(value = 3),
                TestData(value = 4),
                TestData(value = 5),
            ))
            println(collection.find(condition { it.value gt 3 }).toList())
            println(collection.find(condition { it._id eq special._id }).toList())
            collection.updateMany(condition { it._id eq special._id }, modification { it.value assign 2 })
            println(collection.find(condition { it._id eq special._id }).toList())
            collection.updateMany(condition { it._id eq special._id }, modification { it.value += 1 })
            println(collection.find(condition { it._id eq special._id }).toList())
            println(collection.find(Condition.Always()).toList())
            println(collection.find(Condition.Always(), orderBy = listOf(SortPart(path<TestData>().value))).toList())
        }
    }
}

@GenerateDataClassPaths
@Serializable data class TestData(
    val _id: UUID = uuid(),
    @Index val value: Int = 42
)