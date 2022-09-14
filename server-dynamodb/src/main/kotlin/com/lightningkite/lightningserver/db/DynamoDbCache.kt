package com.lightningkite.lightningserver.db

import com.lightningkite.lightningserver.cache.CacheInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.serialization.KSerializer
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import java.time.Duration
import java.time.Instant

class DynamoDbCache(val client: DynamoDbAsyncClient, val tableName: String = "cache"): CacheInterface {
    @OptIn(DelicateCoroutinesApi::class)
    private fun ready() =  GlobalScope.async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
        try {
            val described = client.describeTable { it.tableName(tableName) }.await()
        } catch (e: Exception) {
            client.createTable {
                it.tableName(tableName)
                it.billingMode(BillingMode.PAY_PER_REQUEST)
                it.keySchema(KeySchemaElement.builder().attributeName("key").keyType(KeyType.HASH).build())
                it.attributeDefinitions(
                    AttributeDefinition.builder().attributeName("key").attributeType(ScalarAttributeType.S).build()
                )
            }.await()
            client.updateTimeToLive {
                it.tableName(tableName)
                it.timeToLiveSpecification {
                    it.enabled(true)
                    it.attributeName("expires")
                }
            }
        }
    }
    private var ready = ready()

    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
        ready.await()
        val r = client.getItem {
            it.tableName(tableName)
            it.key(mapOf("key" to AttributeValue.fromS(key)))
        }.await()
        if(r.hasItem()) {
            val item = r.item()
            item["expires"]?.n()?.toLongOrNull()?.let {
                if(System.currentTimeMillis().div(1000L) > it) return null
            }
            return serializer.fromDynamo(item["value"]!!)
        }
        else return null
    }

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) {
        ready.await()
        client.putItem {
            it.tableName(tableName)
            it.item(mapOf(
                "key" to AttributeValue.fromS(key),
                "value" to serializer.toDynamo(value),
            ) + (timeToLive?.let {
                mapOf("expires" to AttributeValue.fromN(Instant.now().plus(it).epochSecond.toString()))
            } ?: mapOf()))
        }.await()
    }

    override suspend fun <T> setIfNotExists(key: String, value: T, serializer: KSerializer<T>): Boolean {
        ready.await()
        try {
            val r = client.putItem {
                it.tableName(tableName)
                it.expressionAttributeNames(mapOf("#k" to "key"))
                it.conditionExpression("attribute_not_exists(#k)")
                it.item(
                    mapOf(
                        "key" to AttributeValue.fromS(key),
                        "value" to serializer.toDynamo(value),
                    )
                )
            }.await()
        } catch(e: ConditionalCheckFailedException) {
            return false
        }
        return true
    }

    override suspend fun add(key: String, value: Int) {
        ready.await()
        client.updateItem {
            it.tableName(tableName)
            it.key(mapOf("key" to AttributeValue.fromS(key)))
            it.updateExpression("ADD #v :v")
            it.expressionAttributeNames(mapOf("#v" to "value"))
            it.expressionAttributeValues(mapOf(":v" to AttributeValue.fromN(value.toString())))
        }.await()
    }

    override suspend fun clear() {
        ready.await()
        ready = ready()
        client.deleteTable { it.tableName(tableName) }.await()
    }

    override suspend fun remove(key: String) {
        ready.await()
        client.deleteItem {
            it.tableName(tableName)
            it.key(mapOf("key" to AttributeValue.fromS(key)))
        }.await()
    }
}