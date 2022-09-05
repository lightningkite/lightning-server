@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
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
import java.util.UUID

class DynamoDBTests() {
    @Test
    fun test() {
        prepareModels()
        runBlocking {
            val dynamo = embeddedDynamo()
            println("Defining table")
            val tableName = "test"
            dynamo.createOrUpdateIdTable(tableName)

            val collection = DynamoDbCollection(
                client = dynamo,
                serializer = TestData.serializer(),
                tableName = tableName
            )

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
            collection.updateMany(condition { it._id eq special._id }, modification { it.value plus 1 })
            println(collection.find(condition { it._id eq special._id }).toList())
        }
    }
}

@DatabaseModel
@Serializable data class TestData(
    val _id: UUID = UUID.randomUUID(),
    @Index val value: Int = 42
)