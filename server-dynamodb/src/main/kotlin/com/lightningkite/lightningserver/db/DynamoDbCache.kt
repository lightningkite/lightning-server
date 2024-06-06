package com.lightningkite.lightningserver.db

import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.CacheSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.datetime.Clock
import com.lightningkite.now
import kotlinx.serialization.KSerializer
import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import kotlin.time.Duration
import kotlinx.datetime.Instant

class DynamoDbCache(val makeClient: () -> DynamoDbAsyncClient, val tableName: String = "cache") : Cache {
    val client by lazy(LazyThreadSafetyMode.SYNCHRONIZED, makeClient)

    companion object {
        init {
            CacheSettings.register("dynamodb") {
                Regex("""dynamodb://(?:(?<access>[^:]+):(?<secret>[^@]+)@)?(?<region>[^/]+)/(?<tableName>.+)""").matchEntire(it.url)?.let { match ->
                    val user = match.groups["access"]?.value ?: ""
                    val password = match.groups["secret"]?.value ?: ""
                    DynamoDbCache(
                        {
                            DynamoDbAsyncClient.builder()
                                .credentialsProvider(
                                    if (user.isNotBlank() && password.isNotBlank()) {
                                        StaticCredentialsProvider.create(object : AwsCredentials {
                                            override fun accessKeyId(): String = user
                                            override fun secretAccessKey(): String = password
                                        })
                                    } else DefaultCredentialsProvider.create()
                                )
                                .region(Region.of(match.groups["region"]!!.value))
                                .build()
                        },
                        match.groups["tableName"]!!.value
                    )
                }
                    ?: throw IllegalStateException("Invalid dynamodb URL. The URL should match the pattern: dynamodb://[access]:[secret]@[region]/[tableName]")
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun ready() = GlobalScope.async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
        try {
            if (client.describeTimeToLive {
                    it.tableName(tableName)
                }.await().timeToLiveDescription().timeToLiveStatus() == TimeToLiveStatus.DISABLED)
                client.updateTimeToLive {
                    it.tableName(tableName)
                    it.timeToLiveSpecification {
                        it.enabled(true)
                        it.attributeName("expires")
                    }
                }.await()
            while (client.describeTable {
                    it.tableName(tableName)
                }.await().table().tableStatus() != TableStatus.ACTIVE) {
                delay(100)
            }
            Unit
        } catch (e: Exception) {
            client.createTable {
                it.tableName(tableName)
                it.billingMode(BillingMode.PAY_PER_REQUEST)
                it.keySchema(KeySchemaElement.builder().attributeName("key").keyType(KeyType.HASH).build())
                it.attributeDefinitions(
                    AttributeDefinition.builder().attributeName("key").attributeType(ScalarAttributeType.S).build()
                )
            }.await()
            while (client.describeTable {
                    it.tableName(tableName)
                }.await().table().tableStatus() != TableStatus.ACTIVE) {
                delay(100)
            }
            client.updateTimeToLive {
                it.tableName(tableName)
                it.timeToLiveSpecification {
                    it.enabled(true)
                    it.attributeName("expires")
                }
            }.await()
            while (client.describeTable {
                    it.tableName(tableName)
                }.await().table().tableStatus() != TableStatus.ACTIVE) {
                delay(100)
            }
            Unit
        }
    }

    private var ready = ready()

    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
        ready.await()
        val r = client.getItem {
            it.tableName(tableName)
            it.consistentRead(true)
            it.key(mapOf("key" to AttributeValue.fromS(key)))
        }.await()
        if (r.hasItem()) {
            val item = r.item()
            item["expires"]?.n()?.toLongOrNull()?.let {
                if (System.currentTimeMillis().div(1000L) > it) return null
            }
            return serializer.fromDynamo(item["value"]!!)
        } else return null
    }

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) {
        ready.await()
        client.putItem {
            it.tableName(tableName)
            it.item(mapOf(
                "key" to AttributeValue.fromS(key),
                "value" to serializer.toDynamo(value),
            ) + (timeToLive?.let {
                mapOf("expires" to AttributeValue.fromN(now().plus(it).epochSeconds.toString()))
            } ?: mapOf()))
        }.await()
    }

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?,
    ): Boolean {
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
                    ) + (timeToLive?.let {
                        mapOf("expires" to AttributeValue.fromN(now().plus(it).epochSeconds.toString()))
                    } ?: mapOf())
                )
            }.await()
        } catch (e: ConditionalCheckFailedException) {
            return false
        }
        return true
    }

    override suspend fun add(key: String, value: Int, timeToLive: Duration?) {
        ready.await()
        client.updateItem {
            it.tableName(tableName)
            it.key(mapOf("key" to AttributeValue.fromS(key)))
            it.updateExpression("SET #exp = :exp, #v = if_not_exists(#v, :z) + :v")
            it.expressionAttributeNames(mapOf("#v" to "value", "#exp" to "expires"))
            it.expressionAttributeValues(
                mapOf(
                    ":z" to AttributeValue.fromN("0"),
                    ":v" to AttributeValue.fromN(value.toString()),
                    ":exp" to (timeToLive?.let { AttributeValue.fromN(now().plus(it).epochSeconds.toString()) }
                        ?: AttributeValue.fromNul(true))
                )
            )
        }.await()
    }

    override suspend fun remove(key: String) {
        ready.await()
        client.deleteItem {
            it.tableName(tableName)
            it.key(mapOf("key" to AttributeValue.fromS(key)))
        }.await()
    }
}