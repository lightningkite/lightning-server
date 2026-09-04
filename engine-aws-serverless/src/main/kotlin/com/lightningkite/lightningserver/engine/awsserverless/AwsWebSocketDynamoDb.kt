@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.lightningserver.engine.awsserverless

import com.lightningkite.lightningserver.runtime.Initiator
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.services.serializers.KotlinBytesFormat
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.NothingSerializer
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.measureTime

/**
 * The outcome of reading one socket's stored row.
 *
 * [Absent] and [Legacy] are separate types rather than one nullable result because they call for
 * opposite handling: an absent row means the socket is already gone and there is nothing left to do,
 * while a legacy row means a live socket is still holding data this deployment cannot use, so the
 * socket has to be ended before it can be forgotten.  Collapsing the two - as a nullable result
 * invites - silently leaves such a socket open to fail again on its next frame.
 */
internal sealed interface SocketRow {
    /** The socket has no row: it disconnected and was cleaned up already. */
    data object Absent : SocketRow

    /**
     * A row written by an earlier deployment, which this one can neither decode nor attribute.
     *
     * Nothing later can repair it.  Both the stored connect request and the initiator are written
     * once, at `${'$'}connect`, by whatever code was deployed then, so the socket is ended and the
     * client reconnects to get a row in the current shape.
     */
    data class Legacy(val reason: String) : SocketRow

    /** A row this deployment can both decode and attribute. */
    class Current(
        val state: ByteArray,
        val connectRequest: WebSocketConnectRequest<*>,
        /**
         * The socket's connect initiator.  Persisted beside the request because each of the five
         * lifecycle phases is a separate Lambda invocation: without it, nothing after
         * `${'$'}connect` could say which socket it belongs to.
         */
        val initiator: Initiator.WebSocket,
    ) : SocketRow
}

internal class AwsWebSocketDynamoDb(
    val client: DynamoDbAsyncClient,
    val baseTableName: String,
    val encoding: KotlinBytesFormat,
) {
    private val socketExpiration = 8.hours
    private val tableSubs = "$baseTableName-ws-subs"
    private val tableSubsReverse = "$baseTableName-ws-subs-reverse"
    private val tableStates = "$baseTableName-ws-state"

    companion object {
        internal val logger =
            KotlinLogging.logger("com.lightningkite.lightningserver.engine.awsserverless.AwsWebSocketDynamoDb")

        // Everything is prefixed with 'ws' because dynamoDB has an absurd amount of reserved keywords.
        private const val socketIdKey = "wsSocketId"
        private const val topicKey = "wsTopic"
        private const val expireKey = "wsExpire"
        private const val pathKey = "wsPath"
        private const val stateKey = "wsState"
        private const val fromStateKey = "wsFromState"
        private const val requestKey = "wsRequest"
        private const val initiatorKey = "wsInitiator"
    }

    private val initMutex = Mutex()
    @Volatile
    private var initialized = false

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
                            { it.attributeName(topicKey).keyType(KeyType.HASH) },
                            { it.attributeName(socketIdKey).keyType(KeyType.RANGE) })
                        it.attributeDefinitions(
                            { it.attributeName(topicKey).attributeType(ScalarAttributeType.S) },
                            { it.attributeName(socketIdKey).attributeType(ScalarAttributeType.S) },
                        )
                        it.globalSecondaryIndexes(
                            {
                                it.keySchema(
                                    { it.attributeName(socketIdKey).keyType(KeyType.HASH) },
                                    { it.attributeName(topicKey).keyType(KeyType.RANGE) })
                                it.projection {
                                    it.projectionType(ProjectionType.INCLUDE).nonKeyAttributes(expireKey, pathKey)
                                }
                                it.indexName(tableSubsReverse)
                            }
                        )
                    },
                    timeToLive = {
                        it.tableName(tableSubs)
                        it.timeToLiveSpecification {
                            it.attributeName(expireKey)
                            it.enabled(true)
                        }
                    }
                )
                client.requireTable(
                    createTableRequest = {
                        it.tableName(tableStates)
                        it.billingMode(BillingMode.PAY_PER_REQUEST)
                        it.keySchema({ it.attributeName(socketIdKey).keyType(KeyType.HASH) })
                        it.attributeDefinitions(
                            { it.attributeName(socketIdKey).attributeType(ScalarAttributeType.S) },
                        )
                    },
                    timeToLive = {
                        it.tableName(tableStates)
                        it.timeToLiveSpecification {
                            it.attributeName(expireKey)
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
                it.expressionAttributeValues(mapOf(":${topicKey}" to AttributeValue.fromS(topic)))
                it.keyConditionExpression("$topicKey = :${topicKey}")
                it.projectionExpression("$socketIdKey, $pathKey")
                it.limit(1000)
            }.asFlow().collect { response ->
                for ((key, value) in response.items().groupBy(
                    keySelector = { it[pathKey]!!.s() },
                    valueTransform = { it[socketIdKey]!!.s() }
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
                        topicKey to AttributeValue.fromS(topic),
                        socketIdKey to AttributeValue.fromS(socketId),
                        pathKey to AttributeValue.fromS(path),
                        expireKey to AttributeValue.fromN(
                            Clock.System.now().plus(socketExpiration).epochSeconds.toString()
                        )
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
                        topicKey to AttributeValue.fromS(topic),
                        socketIdKey to AttributeValue.fromS(socketId),
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
                it.expressionAttributeValues(mapOf(":$socketIdKey" to AttributeValue.fromS(socketId)))
                it.keyConditionExpression("$socketIdKey = :$socketIdKey")
                it.projectionExpression("$topicKey, $socketIdKey")
                it.limit(100)
            }.asFlow().collect { page ->
                val keys = page.items().map { item ->
                    item.filter { (k, _) -> k == topicKey || k == socketIdKey }
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
                it.key(mapOf(socketIdKey to AttributeValue.fromS(socketId)))
            }.await()
        }.also { logger.debug { "AwsWebSocketDynamoDb.clean took $it" } }
    }

    /**
     * Reads one stored row, reporting anything an earlier deployment wrote as [SocketRow.Legacy]
     * instead of throwing.
     *
     * This is not defensive padding.  The stored connect request is a positional blob, and this branch
     * removed two properties from [WebSocketConnectRequest], so every blob written before that change
     * carries element indices past the end of the current descriptor.  A row written before the
     * `wsInitiator` column existed is the same situation with a different symptom.  Neither is a fault
     * to report; both are simply data this deployment cannot read.
     *
     * Only the two failures a schema change produces are absorbed, and only around the decode itself:
     * [SerializationException] for a missing field or an out-of-range element index, and [IOException]
     * for a decode that ran off the end of the blob because the layout shifted under it.  Every other
     * failure, DynamoDB's included, happens outside this block and still propagates.
     */
    private fun decodeRow(socketId: String, item: Map<String, AttributeValue>): SocketRow {
        val initiator = item[initiatorKey]
            ?: return legacyRow(socketId, "$initiatorKey is absent, so the socket cannot be attributed")
        return try {
            SocketRow.Current(
                state = item[stateKey]!!.b().asByteArray(),
                connectRequest = encoding.decodeFromByteArray(
                    WebSocketConnectRequest.serializer(NothingSerializer()),
                    item[requestKey]!!.b().asByteArray()
                ),
                initiator = encoding.decodeFromByteArray(
                    Initiator.WebSocket.serializer(),
                    initiator.b().asByteArray()
                ),
            )
        } catch (e: SerializationException) {
            legacyRow(
                socketId,
                "its stored data does not fit this deployment's schema (${e.message ?: e::class.simpleName})"
            )
        } catch (e: IOException) {
            legacyRow(
                socketId,
                "its stored data ran out mid-decode, so its layout is another schema's " +
                        "(${e.message ?: e::class.simpleName})"
            )
        }
    }

    private fun legacyRow(socketId: String, reason: String): SocketRow.Legacy {
        logger.warn {
            "Socket $socketId holds a row from a previous deployment: $reason. " +
                    "The socket will be ended so the client reconnects."
        }
        return SocketRow.Legacy(reason)
    }

    suspend fun debugStates(): Map<String, SocketRow> {
        ensureTables()
        val out = HashMap<String, SocketRow>()
        client.scanPaginator {
            it.tableName(tableStates)
            it.projectionExpression("$socketIdKey, $stateKey, $requestKey, $initiatorKey")
        }.asFlow().collect {
            it.items()?.forEach {
                val id = it[socketIdKey]!!.s()
                out[id] = decodeRow(id, it)
            }
        }
        return out
    }

    // Every state read below uses a consistent read.  Socket state is guarded by the compare-and-swap in
    // updateState, and a stale read there is not merely out of date - it hands back a comparison value that
    // is guaranteed to lose the swap, or omits a row that was written moments ago by the connect handler.
    // Correctness here is worth the doubled read cost.

    suspend fun state(id: String): SocketRow {
        ensureTables()
        val result: SocketRow
        measureTime {
            val response = client.getItem {
                it.tableName(tableStates)
                it.key(mapOf(socketIdKey to AttributeValue.fromS(id)))
                it.projectionExpression("$stateKey, $requestKey, $initiatorKey")
                it.consistentRead(true)
            }.await()
            // item() is an SDK auto-construct map: it returns empty rather than null when the socket
            // has no row, so hasItem() is the only way to detect a missing socket.  Testing item() for
            // null instead silently falls through to reading attributes that aren't there.
            result = if (!response.hasItem()) SocketRow.Absent else decodeRow(id, response.item())
        }.also { logger.debug { "AwsWebSocketDynamoDb.state($id) took $it" } }
        return result
    }

    suspend fun statesAlone(ids: Iterable<String>): Map<String, ByteArray> {
        ensureTables()
        val out = HashMap<String, ByteArray>()
        measureTime {
            val getState = KeysAndAttributes.builder()
                .projectionExpression("$socketIdKey, $stateKey")
                .consistentRead(true)
                .keys(ids.map { mapOf(socketIdKey to AttributeValue.fromS(it)) }).build()
            client.batchGetItemPaginator {
                it.requestItems(mapOf(tableStates to getState))
            }.asFlow().collect {
                it.responses()?.get(tableStates)?.forEach {
                    out[it[socketIdKey]!!.s()] = it[stateKey]!!.b().asByteArray()
                }
            }
        }//.also { logger.debug { "AwsWebSocketDynamoDb.statesAlone(${ids.joinToString()}) took $it" } }
        return out
    }

    /**
     * Reads several rows at once.  Every requested id is present in the result, defaulting to
     * [SocketRow.Absent], so a caller walking its own id list has to handle a missing socket
     * explicitly instead of inferring it from a key that isn't there.
     */
    suspend fun states(ids: Iterable<String>): Map<String, SocketRow> {
        ensureTables()
        val out = HashMap<String, SocketRow>()
        ids.forEach { out[it] = SocketRow.Absent }
        measureTime {
            val getState = KeysAndAttributes.builder()
                .projectionExpression("$socketIdKey, $stateKey, $requestKey, $initiatorKey")
                .consistentRead(true)
                .keys(ids.map { mapOf(socketIdKey to AttributeValue.fromS(it)) }).build()
            client.batchGetItemPaginator {
                it.requestItems(mapOf(tableStates to getState))
            }.asFlow().collect {
                it.responses()?.get(tableStates)?.forEach {
                    val id = it[socketIdKey]!!.s()
                    out[id] = decodeRow(id, it)
                }
            }
        }.also { logger.debug { "AwsWebSocketDynamoDb.states(${ids.joinToString()}) took $it" } }
        return out
    }

    suspend fun setState(
        socketId: String,
        request: WebSocketConnectRequest<*>,
        initiator: Initiator.WebSocket,
        toState: ByteArray,
    ) {
        ensureTables()
        measureTime {
            client.putItem {
                it.tableName(tableStates)
                @Suppress("UNCHECKED_CAST")
                it.item(
                    mapOf(
                        socketIdKey to AttributeValue.fromS(socketId),
                        stateKey to AttributeValue.fromB(SdkBytes.fromByteArray(toState)),
                        requestKey to AttributeValue.fromB(
                            SdkBytes.fromByteArray(
                                encoding.encodeToByteArray(
                                    WebSocketConnectRequest.serializer(NothingSerializer()),
                                    request as WebSocketConnectRequest<Nothing>
                                )
                            )
                        ),
                        initiatorKey to AttributeValue.fromB(
                            SdkBytes.fromByteArray(
                                encoding.encodeToByteArray(Initiator.WebSocket.serializer(), initiator)
                            )
                        ),
                        expireKey to AttributeValue.fromN(
                            Clock.System.now().plus(socketExpiration).epochSeconds.toString()
                        )
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
                    it.key(mapOf(socketIdKey to AttributeValue.fromS(socketId)))
                    it.expressionAttributeValues(
                        mapOf(
                            ":$fromStateKey" to AttributeValue.fromB(SdkBytes.fromByteArray(fromState)),
                            ":$stateKey" to AttributeValue.fromB(SdkBytes.fromByteArray(toState)),
                            ":$expireKey" to AttributeValue.fromN(
                                Clock.System.now().plus(socketExpiration).epochSeconds.toString()
                            ),
                        )
                    )
                    it.conditionExpression("$stateKey = :$fromStateKey")
                    it.updateExpression("SET $stateKey = :$stateKey, $expireKey = :$expireKey")
                }.await()
            }.also { logger.debug { "AwsWebSocketDynamoDb.updateState($socketId) took $it" } }
            true
        } catch (e: ConditionalCheckFailedException) {
            // This is expected during optimistic locking retries, logged at debug level in commit()
            logger.debug { "Optimistic lock failed for $socketId (expected during concurrent updates)" }
            false
        }
    }
}