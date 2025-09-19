@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.services.data.KotlinBytesFormat
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.NothingSerializer
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes
import software.amazon.awssdk.services.dynamodb.model.ProjectionType
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.measureTime

internal class AwsWebSocketDynamoDb(
    val client: DynamoDbAsyncClient,
    val baseTableName: String,
    val encoding: KotlinBytesFormat
) {
    class StateAndConnectRequest(val state: ByteArray, val connectRequest: WebSocketConnectRequest<*>)

    private val socketExpiration = 8.hours
    private val tableSubs = "$baseTableName-subs"
    private val tableSubsReverse = "$baseTableName-subs-reverse"
    private val tableStates = "$baseTableName-state"

    companion object {
        internal val logger = KotlinLogging.logger("com.lightningkite.lightningserver.engine.awsserverless.AwsWebSocketDynamoDb")
    }

    private val initMutex = Mutex()
    @Volatile private var initialized = false

    internal suspend fun ensureTables() {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            measureTime {
                client.requireTable(
                    createTableRequest = {
                        it.tableName(tableSubs)
                        it.billingMode(BillingMode.PAY_PER_REQUEST)
                        it.keySchema(
                            { it.attributeName("topic").keyType(KeyType.HASH) },
                            { it.attributeName("socketId").keyType(KeyType.RANGE) })
                        it.attributeDefinitions(
                            { it.attributeName("topic").attributeType(ScalarAttributeType.S) },
                            { it.attributeName("socketId").attributeType(ScalarAttributeType.S) },
                        )
                        it.globalSecondaryIndexes(
                            {
                                it.keySchema(
                                    { it.attributeName("socketId").keyType(KeyType.HASH) },
                                    { it.attributeName("topic").keyType(KeyType.RANGE) })
                                it.projection {
                                    it.projectionType(ProjectionType.INCLUDE).nonKeyAttributes("path", "expire")
                                }
                                it.indexName(tableSubsReverse)
                            }
                        )
                    },
                    timeToLive = {
                        it.tableName(tableSubs)
                        it.timeToLiveSpecification {
                            it.attributeName("expire")
                            it.enabled(true)
                        }
                    }
                )
                client.requireTable(
                    createTableRequest = {
                        it.tableName(tableStates)
                        it.billingMode(BillingMode.PAY_PER_REQUEST)
                        it.keySchema({ it.attributeName("socketId").keyType(KeyType.HASH) })
                        it.attributeDefinitions(
                            { it.attributeName("socketId").attributeType(ScalarAttributeType.S) },
                        )
                    },
                    timeToLive = {
                        it.tableName(tableStates)
                        it.timeToLiveSpecification {
                            it.attributeName("expire")
                            it.enabled(true)
                        }
                    }
                )
            }.also { logger.debug { "AwsWebSocketDynamoDb.ensureTables took $it" } }
            initialized = true
        }
    }

    suspend fun subscribers(topic: String): Map<String, Set<String>> {
        val out = HashMap<String, HashSet<String>>()
        forSubscribers(topic) { path, ids -> out.getOrPut(path) { HashSet() }.addAll(ids) }
        return out
    }

    suspend fun forSubscribers(topic: String, perSubscriber: suspend (path: String, ids: Collection<String>) -> Unit) {
        ensureTables()
        measureTime {
            client.queryPaginator {
                it.tableName(tableSubs)
                it.expressionAttributeValues(mapOf(":topic" to AttributeValue.fromS(topic)))
                it.keyConditionExpression("topic = :topic")
                it.expressionAttributeNames(mapOf("#path" to "path"))
                it.projectionExpression("socketId, #path")
                it.limit(1000)
            }.asFlow().collect { response ->
                for ((key, value) in response.items().groupBy(
                    keySelector = { it["path"]!!.s() },
                    valueTransform = { it["socketId"]!!.s() }
                ).entries) {
                    perSubscriber(key, value)
                }
            }
        }.also { logger.debug { "AwsWebSocketDynamoDb.forSubscribers took $it" } }
    }

    suspend fun subscribe(path: String, topic: String, socketId: String) {
        ensureTables()
        measureTime {
            client.putItem {
                it.tableName(tableSubs)
                it.item(
                    mapOf(
                        "topic" to AttributeValue.fromS(topic),
                        "socketId" to AttributeValue.fromS(socketId),
                        "path" to AttributeValue.fromS(path),
                        "expire" to AttributeValue.fromN(Clock.System.now().plus(socketExpiration).epochSeconds.toString())
                    )
                )
            }.await()
        }.also { logger.debug { "AwsWebSocketDynamoDb.subscribe took $it" } }
    }

    suspend fun unsubscribe(topic: String, socketId: String) {
        ensureTables()
        measureTime {
            client.deleteItem {
                it.tableName(tableSubs)
                it.key(
                    mapOf(
                        "topic" to AttributeValue.fromS(topic),
                        "socketId" to AttributeValue.fromS(socketId),
                    )
                )
            }.await()
        }.also { logger.debug { "AwsWebSocketDynamoDb.unsubscribe took $it" } }
    }

    suspend fun clean(socketId: String) {
        ensureTables()
        measureTime {
            client.queryPaginator {
                it.tableName(tableSubs)
                it.indexName(tableSubsReverse)
                it.expressionAttributeValues(mapOf(":socketId" to AttributeValue.fromS(socketId)))
                it.keyConditionExpression("socketId = :socketId")
                it.projectionExpression("topic, socketId")
                it.limit(100)
            }.asFlow().collect { page ->
                val keys = page.items().map { item ->
                    item.filter { (k, _) -> k == "topic" || k == "socketId" }
                }
                keys.chunked(25).forEach { batch ->
                    client.batchWriteItem {
                        it.requestItems(
                            mapOf(
                                tableSubs to batch.map { k ->
                                    software.amazon.awssdk.services.dynamodb.model.WriteRequest.builder()
                                        .deleteRequest(
                                            software.amazon.awssdk.services.dynamodb.model.DeleteRequest.builder()
                                                .key(k)
                                                .build()
                                        )
                                        .build()
                                }
                            )
                        )
                    }.await()
                }
            }
            client.deleteItem {
                it.tableName(tableStates)
                it.key(mapOf("socketId" to AttributeValue.fromS(socketId)))
            }.await()
        }.also { logger.debug { "AwsWebSocketDynamoDb.clean took $it" } }
    }

    suspend fun debugStates(): Map<String, StateAndConnectRequest> {
        ensureTables()
        val out = HashMap<String, StateAndConnectRequest>()
        client.scanPaginator {
            it.tableName(tableStates)
            it.projectionExpression("socketId, state, request")
        }.asFlow().collect {
            it.items()?.forEach {
                out[it["socketId"]!!.s()] = StateAndConnectRequest(
                    it["state"]!!.b().asByteArray(),
                    encoding.decodeFromByteArray(WebSocketConnectRequest.serializer(NothingSerializer()), it["request"]!!.b().asByteArray())
                )
            }
        }
        return out
    }

    suspend fun state(id: String): StateAndConnectRequest? {
        ensureTables()
        val result: StateAndConnectRequest?
        measureTime {
            result = client.getItem {
                it.tableName(tableStates)
                it.key(mapOf("socketId" to AttributeValue.fromS(id)))
                it.projectionExpression("state, request")
            }.await().item()?.let {
                StateAndConnectRequest(
                    it["state"]!!.b().asByteArray(),
                    encoding.decodeFromByteArray(WebSocketConnectRequest.serializer(NothingSerializer()), it.get("request")!!.b().asByteArray()),
                )
            }
        }.also { logger.debug { "AwsWebSocketDynamoDb.state($id) took $it" } }
        return result
    }

    suspend fun statesAlone(ids: Iterable<String>): Map<String, ByteArray> {
        ensureTables()
        val out = HashMap<String, ByteArray>()
        measureTime {
            val getState = KeysAndAttributes.builder().projectionExpression("socketId, state")
                .keys(ids.map { mapOf("socketId" to AttributeValue.fromS(it)) }).build()
            client.batchGetItemPaginator {
                it.requestItems(mapOf(tableStates to getState))
            }.asFlow().collect {
                it.responses()?.get(tableStates)?.forEach {
                    out[it["socketId"]!!.s()] = it["state"]!!.b().asByteArray()
                }
            }
        }.also { logger.debug { "AwsWebSocketDynamoDb.statesAlone(${ids.joinToString()}) took $it" } }
        return out
    }

    suspend fun states(ids: Iterable<String>): Map<String, StateAndConnectRequest> {
        ensureTables()
        val out = HashMap<String, StateAndConnectRequest>()
        measureTime {
            val getState = KeysAndAttributes.builder().projectionExpression("socketId, state, request")
                .keys(ids.map { mapOf("socketId" to AttributeValue.fromS(it)) }).build()
            client.batchGetItemPaginator {
                it.requestItems(mapOf(tableStates to getState))
            }.asFlow().collect {
                it.responses()?.get(tableStates)?.forEach {
                    out[it["socketId"]!!.s()] = StateAndConnectRequest(
                        it["state"]!!.b().asByteArray(),
                        encoding.decodeFromByteArray(WebSocketConnectRequest.serializer(NothingSerializer()), it["request"]!!.b().asByteArray())
                    )
                }
            }
        }.also { logger.debug { "AwsWebSocketDynamoDb.states(${ids.joinToString()}) took $it" } }
        return out
    }

    suspend fun setState(socketId: String, request: WebSocketConnectRequest<*>, toState: ByteArray) {
        ensureTables()
        measureTime {
            client.putItem {
                it.tableName(tableStates)
                @Suppress("UNCHECKED_CAST")
                it.item(
                    mapOf(
                        "socketId" to AttributeValue.fromS(socketId),
                        "state" to AttributeValue.fromB(SdkBytes.fromByteArray(toState)),
                        "request" to AttributeValue.fromB(
                            SdkBytes.fromByteArray(
                                encoding.encodeToByteArray(
                                    WebSocketConnectRequest.serializer(NothingSerializer()),
                                    request as WebSocketConnectRequest<Nothing>
                                )
                            )
                        ),
                        "expire" to AttributeValue.fromN(Clock.System.now().plus(socketExpiration).epochSeconds.toString())
                    )
                )
            }.await()
        }.also { logger.debug { "AwsWebSocketDynamoDb.setState($socketId) took $it" } }
    }

    suspend fun updateState(socketId: String, fromState: ByteArray, toState: ByteArray): Boolean {
        ensureTables()
        return try {
            measureTime {
                client.updateItem {
                    it.tableName(tableStates)
                    it.key(mapOf("socketId" to AttributeValue.fromS(socketId)))
                    it.expressionAttributeNames(mapOf("#state" to "state", "#expire" to "expire"))
                    it.expressionAttributeValues(
                        mapOf(
                            ":fromState" to AttributeValue.fromB(SdkBytes.fromByteArray(fromState)),
                            ":state" to AttributeValue.fromB(SdkBytes.fromByteArray(toState)),
                            ":expire" to AttributeValue.fromN(Clock.System.now().plus(socketExpiration).epochSeconds.toString()),
                        )
                    )
                    it.conditionExpression("#state = :fromState")
                    it.updateExpression("SET #state = :state, #expire = :expire")
                }.await()
            }.also { logger.debug { "AwsWebSocketDynamoDb.updateState($socketId) took $it" } }
            true
        } catch (_: ConditionalCheckFailedException) {
            false
        }
    }
}