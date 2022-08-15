package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningdb.Condition
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow
import kotlinx.serialization.KSerializer
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import kotlin.reflect.KProperty1

class DynamoDbCollection<T : Any>(
    val client: DynamoDbAsyncClient,
    val serializer: KSerializer<T>,
    val tableName: String
) : AbstractSignalFieldCollection<T>() {

    val idSerializer = serializer.fieldSerializer("_id") as? KSerializer<Any>

    suspend fun findRaw(
        condition: Condition<T>,
        orderBy: List<SortPart<T>> = listOf(),
        skip: Int = 0,
        limit: Int = Int.MAX_VALUE,
        maxQueryMs: Long = 30_000L
    ): Flow<Pair<Map<String, AttributeValue>, T>> {
        val c = condition.dynamo(serializer)
        if (c.never) return emptyFlow()
        val parsed = if (c.writeKey != null) {
            client.queryPaginator {
                it.tableName(tableName)
                if (c.local == null) it.limit(limit + skip)
                it.apply(c)
            }.items().map { it to serializer.fromDynamoMap(it) }.asFlow()
        } else {
            client.scanPaginator {
                it.tableName(tableName)
                if (c.local == null) it.limit(limit)
                it.apply(c)
            }.items().map { it to serializer.fromDynamoMap(it) }.asFlow()
        }
        return c.local?.let { parsed.filter { i -> it(i.second) } } ?: parsed
    }

    override suspend fun find(
        condition: Condition<T>,
        orderBy: List<SortPart<T>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
    ): Flow<T> = findRaw(condition, orderBy, skip, limit, maxQueryMs).map { it.second }

    override suspend fun insertImpl(models: List<T>): List<T> {
        client.batchWriteItem {
            it.requestItems(mapOf(tableName to models.map {
                WriteRequest.builder().putRequest(
                    PutRequest.builder().item(serializer.toDynamoMap(it)).build()
                ).build()
            }))
        }.await()
        return models
    }

    override suspend fun replaceOneImpl(condition: Condition<T>, model: T): EntryChange<T> {
        TODO("Not yet implemented")
    }

    override suspend fun replaceOneIgnoringResultImpl(condition: Condition<T>, model: T): Boolean {
        TODO("Not yet implemented")
    }

    override suspend fun upsertOneImpl(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T
    ): EntryChange<T> {
        TODO("Not yet implemented")
    }

    override suspend fun upsertOneIgnoringResultImpl(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T
    ): Boolean {
        TODO("Not yet implemented")
    }

    suspend fun <R> perKey(
        condition: Condition<T>,
        limit: Int = Int.MAX_VALUE,
        action: suspend (condition: DynamoCondition<T>, key: Map<String, AttributeValue>) -> R
    ): Flow<R> {
        val c = condition.dynamo(serializer)
        if (c.never) return emptyFlow()
        val exactKey = condition.exactPrimaryKey()
        if (exactKey != null && idSerializer != null) {
            return flowOf(action(c, mapOf("_id" to idSerializer.toDynamo(exactKey))))
        } else {
            return findRaw(condition = condition, limit = limit).map {
                action(c, mapOf("_id" to it.first["_id"]!!))
            }
        }
    }

    override suspend fun updateOneImpl(condition: Condition<T>, modification: Modification<T>): EntryChange<T> {
        return perKey(condition, limit = 1) { c, key ->
            val result = client.updateItem {
                it.tableName(tableName)
                it.returnValues(ReturnValue.ALL_OLD)
                it.apply(c)
                it.key(key)
                it.updateExpression()
            }.await()
            val o = serializer.fromDynamoMap(result.attributes())
            EntryChange(
                old = o,
                new = modification(o)
            )
        }.singleOrNull() ?: EntryChange(null, null)
    }

    override suspend fun updateOneIgnoringResultImpl(condition: Condition<T>, modification: Modification<T>): Boolean {
        return perKey(condition, limit = 1) { c, key ->
            client.updateItem {
                it.tableName(tableName)
                it.apply(c)
                it.key(key)
            }.await()
            true
        }.singleOrNull() ?: false
    }

    override suspend fun updateManyImpl(condition: Condition<T>, modification: Modification<T>): CollectionChanges<T> {
        val changes = ArrayList<EntryChange<T>>()
        perKey(condition) { c, key ->
            val result = client.updateItem {
                it.tableName(tableName)
                it.returnValues(ReturnValue.ALL_OLD)
                it.apply(c)
                it.key(key)
            }.await()
            val o = serializer.fromDynamoMap(result.attributes())
            EntryChange(
                old = o,
                new = modification(o)
            )
        }.collect { changes.add(it) }
        return CollectionChanges(changes = changes)
    }

    override suspend fun updateManyIgnoringResultImpl(condition: Condition<T>, modification: Modification<T>): Int {
        var changed = 0
        perKey(condition) { c, key ->
            val result = client.updateItem {
                it.tableName(tableName)
                it.apply(c)
                it.key(key)
            }.await()
            changed++
        }.collect { }
        return changed
    }

    override suspend fun deleteOneImpl(condition: Condition<T>): T? {
        return perKey(condition, limit = 1) { c, key ->
            val result = client.deleteItem {
                it.tableName(tableName)
                it.returnValues(ReturnValue.ALL_OLD)
                it.apply(c)
                it.key(key)
            }.await()
            serializer.fromDynamoMap(result.attributes())
        }.singleOrNull()
    }

    override suspend fun deleteOneIgnoringOldImpl(condition: Condition<T>): Boolean {
        return perKey(condition, limit = 1) { c, key ->
            client.deleteItem {
                it.tableName(tableName)
                it.apply(c)
                it.key(key)
            }.await()
            true
        }.singleOrNull() ?: false
    }

    override suspend fun deleteManyImpl(condition: Condition<T>): List<T> {
        return perKey(condition) { c, key ->
            val result = client.deleteItem {
                it.tableName(tableName)
                it.returnValues(ReturnValue.ALL_OLD)
                it.apply(c)
                it.key(key)
            }.await()
            val o = serializer.fromDynamoMap(result.attributes())
            o
        }.toList()
    }

    override suspend fun deleteManyIgnoringOldImpl(condition: Condition<T>): Int {
        var changed = 0
        perKey(condition) { c, key ->
            val result = client.deleteItem {
                it.tableName(tableName)
                it.apply(c)
                it.key(key)
            }.await()
            changed++
        }.collect { }
        return changed
    }

    override suspend fun count(condition: Condition<T>): Int {
        if(condition is Condition.Always)
            return client.describeTable { it.tableName(tableName) }.await().table().itemCount().toInt()
        else {
            val c = condition.dynamo(serializer)
            if (c.never) return 0
            if (c.local == null) {
                val parsed = if (c.writeKey != null) {
                    client.queryPaginator {
                        it.tableName(tableName)
                        it.attributesToGet()
                        it.apply(c)
                    }.items().asFlow()
                } else {
                    client.scanPaginator {
                        it.tableName(tableName)
                        it.attributesToGet()
                        it.apply(c)
                    }.items().asFlow()
                }
                return parsed.count()
            } else {
                return find(condition).filter { condition(it) }.count()
            }
        }
    }

    override suspend fun <Key> groupCount(condition: Condition<T>, groupBy: KProperty1<T, Key>): Map<Key, Int> {
        val map = HashMap<Key, Int>()
        find(condition).collect {
            val key = groupBy.get(it)
            map[key] = map.getOrDefault(key, 0)
        }
        return map
    }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        property: KProperty1<T, N>
    ): Double? {
        val a = aggregate.aggregator()
        find(condition).collect {
            property.get(it)?.toDouble()?.let { a.consume(it) }
        }
        return a.complete()
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        groupBy: KProperty1<T, Key>,
        property: KProperty1<T, N>
    ): Map<Key, Double?> {
        val map = HashMap<Key, Aggregator>()
        find(condition).collect {
            val key = groupBy.get(it)
            property.get(it)?.toDouble()?.let { map.getOrPut(key) { aggregate.aggregator() }.consume(it) }
        }
        return map.mapValues { it.value.complete() }
    }


}

fun Condition<*>.singleValue(): Any? = when(this) {
    is Condition.Equal -> this.value
    is Condition.And -> this.conditions.asSequence().mapNotNull { it.singleValue() }.firstOrNull()
    else -> null
}

fun Condition<*>.exactPrimaryKey(): Any? {
    return when(this) {
        is Condition.And -> this.conditions.asSequence().mapNotNull { it.exactPrimaryKey() }.firstOrNull()
        is Condition.OnField<*, *> -> if(this.key.name == "_id") this.condition.singleValue() else null
        else -> null
    }
}

suspend fun DynamoDbAsyncClient.createOrUpdateIdTable(tableName: String) {
    try {
        describeTable { it.tableName(tableName) }.await()
    } catch (e: Exception) {
        createTable {
            it.tableName(tableName)
            it.billingMode(BillingMode.PAY_PER_REQUEST)
            it.keySchema(KeySchemaElement.builder().attributeName("_id").keyType(KeyType.HASH).build())
            it.attributeDefinitions(
                AttributeDefinition.builder().attributeName("_id").attributeType(ScalarAttributeType.S).build()
            )
        }.await()
    }
}
