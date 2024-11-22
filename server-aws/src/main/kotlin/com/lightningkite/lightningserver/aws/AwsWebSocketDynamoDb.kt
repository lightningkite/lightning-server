package com.lightningkite.lightningserver.aws

import com.lightningkite.lightningserver.db.requireTable
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.serialization.InternalCommunicationEncoding
import com.lightningkite.lightningserver.websocket.WebSocketConnectRequest
import com.lightningkite.now
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.collect
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.AttributeAction
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.AttributeValueUpdate
import software.amazon.awssdk.services.dynamodb.model.BillingMode
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.dynamodb.model.KeyType
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes
import software.amazon.awssdk.services.dynamodb.model.ProjectionType
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlin.time.Duration.Companion.hours
import kotlin.time.measureTime

class AwsWebSocketDynamoDb(
    val client: DynamoDbAsyncClient,
    val baseTableName: String,
    val encoding: InternalCommunicationEncoding = engine.internalCommunicationEncoding
) {
    class StateAndConnectRequest(val state: ByteArray, val connectRequest: WebSocketConnectRequest)

    val socketExpiration = 8.hours
    val tableSubs = "$baseTableName-subs"
    val tableSubsReverse = "$baseTableName-subs-reverse"
    val tableStates = "$baseTableName-state"
    val ready = GlobalScope.async(start = CoroutineStart.LAZY) {
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
//                    { it.attributeName("path").attributeType(ScalarAttributeType.S) },
//                    { it.attributeName("expire").attributeType(ScalarAttributeType.N) },
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
        }.also { println("AwsWebSocketDynamoDb.ready took $it") }
        Unit
    }

    suspend fun subscribers(topic: String): Map<String, Set<String>> {
        val out = HashMap<String, HashSet<String>>()
        forSubscribers(topic) { path, ids -> out.getOrPut(path) { HashSet() }.addAll(ids) }
        return out
    }

    suspend fun forSubscribers(topic: String, perSubscriber: suspend (path: String, ids: Iterable<String>) -> Unit) {
        ready.await()
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
        }.also { println("AwsWebSocketDynamoDb.forSubscribers took $it") }
    }

    suspend fun subscribe(path: String, topic: String, socketId: String) {
        ready.await()
        measureTime {
            client.putItem {
                it.tableName(tableSubs)
                it.item(
                    mapOf(
                        "topic" to AttributeValue.fromS(topic),
                        "socketId" to AttributeValue.fromS(socketId),
                        "path" to AttributeValue.fromS(path),
                        "expires" to AttributeValue.fromN(now().plus(socketExpiration).epochSeconds.toString())
                    )
                )
            }.await()
        }.also { println("AwsWebSocketDynamoDb.subscribe took $it") }
    }

    suspend fun unsubscribe(topic: String, socketId: String) {
        ready.await()
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
        }.also { println("AwsWebSocketDynamoDb.unsubscribe took $it") }
    }

    suspend fun clean(socketId: String) {
        ready.await()
        measureTime {
            client.queryPaginator {
                it.tableName(tableSubs)
                it.indexName(tableSubsReverse)
                it.expressionAttributeValues(mapOf(":socketId" to AttributeValue.fromS(socketId)))
                it.keyConditionExpression("socketId = :socketId")
                it.projectionExpression("topic, socketId")
                it.limit(100)
            }.collect {
                for (item in it.items()) {
                    client.deleteItem {
                        it.tableName(tableSubs)
                        it.key(item)
                    }.await()
                }
            }
            client.deleteItem {
                it.tableName(tableStates)
                it.key(mapOf("socketId" to AttributeValue.fromS(socketId)))
            }.await()
        }.also { println("AwsWebSocketDynamoDb.clean took $it") }
    }

    suspend fun debugStates(): Map<String, StateAndConnectRequest> {
        ready.await()
        val out = HashMap<String, StateAndConnectRequest>()
        client.scanPaginator {
            it.tableName(tableStates)
            it.attributesToGet("socketId", "state", "request")
        }.asFlow().collect {
            it.items()?.forEach {
                out[it["socketId"]!!.s()] = StateAndConnectRequest(
                    it["state"]!!.b().asByteArray(),
                    encoding.decodeBytes(WebSocketConnectRequest.serializer(), it["request"]!!.b().asByteArray())
                )
            }
        }
        return out
    }

    suspend fun state(id: String): StateAndConnectRequest? {
        ready.await()
        measureTime {
            return client.getItem {
                it.tableName(tableStates)
                it.key(mapOf("socketId" to AttributeValue.fromS(id)))
                it.attributesToGet("state", "request")
            }.await().item()?.let {
                StateAndConnectRequest(
                    it.get("state")!!.b().asByteArray(),
                    encoding.decodeBytes(WebSocketConnectRequest.serializer(), it.get("request")!!.b().asByteArray()),
                )
            }
        }.also { println("AwsWebSocketDynamoDb.state($id) took $it") }
    }

    suspend fun statesAlone(ids: Iterable<String>): Map<String, ByteArray> {
        ready.await()
        val out = HashMap<String, ByteArray>()
        measureTime {
            val getState = KeysAndAttributes.builder().attributesToGet("socketId", "state")
                .keys(ids.map { mapOf("socketId" to AttributeValue.fromS(it)) }).build()
            client.batchGetItemPaginator {
                it.requestItems(mapOf(tableStates to getState))
            }.asFlow().collect {
                it.responses()?.get(tableStates)?.forEach {
                    out[it["socketId"]!!.s()] = it["state"]!!.b().asByteArray()
                }
            }
        }.also { println("AwsWebSocketDynamoDb.statesAlone(${ids.joinToString()}) took $it") }
        return out
    }

    suspend fun states(ids: Iterable<String>): Map<String, StateAndConnectRequest> {
        ready.await()
        val out = HashMap<String, StateAndConnectRequest>()
        measureTime {
            val getState = KeysAndAttributes.builder().attributesToGet("socketId", "state", "request")
                .keys(ids.map { mapOf("socketId" to AttributeValue.fromS(it)) }).build()
            client.batchGetItemPaginator {
                it.requestItems(mapOf(tableStates to getState))
            }.asFlow().collect {
                it.responses()?.get(tableStates)?.forEach {
                    out[it["socketId"]!!.s()] = StateAndConnectRequest(
                        it["state"]!!.b().asByteArray(),
                        encoding.decodeBytes(WebSocketConnectRequest.serializer(), it["request"]!!.b().asByteArray())
                    )
                }
            }
        }.also { println("AwsWebSocketDynamoDb.states(${ids.joinToString()}) took $it") }
        return out
    }

    suspend fun setState(socketId: String, request: WebSocketConnectRequest, toState: ByteArray) {
        ready.await()
        measureTime {
            client.putItem {
                it.tableName(tableStates)
                it.item(
                    mapOf(
                        "socketId" to AttributeValue.fromS(socketId),
                        "state" to AttributeValue.fromB(SdkBytes.fromByteArray(toState)),
                        "request" to AttributeValue.fromB(
                            SdkBytes.fromByteArray(
                                encoding.encodeBytes(
                                    WebSocketConnectRequest.serializer(),
                                    request
                                )
                            )
                        ),
                        "expires" to AttributeValue.fromN(now().plus(socketExpiration).epochSeconds.toString())
                    )
                )
            }.await()
        }.also { println("AwsWebSocketDynamoDb.setState($socketId) took $it") }
    }

    suspend fun updateState(socketId: String, fromState: ByteArray, toState: ByteArray): Boolean {
        ready.await()
        return try {
            measureTime {
                client.updateItem {
                    it.tableName(tableStates)
                    it.key(mapOf("socketId" to AttributeValue.fromS(socketId)))
                    it.expressionAttributeNames(mapOf("#state" to "state", "#expires" to "expires"))
                    it.expressionAttributeValues(
                        mapOf(
                            ":fromState" to AttributeValue.fromB(SdkBytes.fromByteArray(fromState)),
                            ":state" to AttributeValue.fromB(SdkBytes.fromByteArray(toState)),
                            ":expires" to AttributeValue.fromN(now().plus(socketExpiration).epochSeconds.toString()),
                        )
                    )
                    it.conditionExpression("#state = :fromState")
                    it.updateExpression("SET #state = :state, #expires = :expires")
                }.await()
            }.also { println("AwsWebSocketDynamoDb.updateState($socketId) took $it") }
            true
        } catch (e: ConditionalCheckFailedException) {
            false
        }
    }
}