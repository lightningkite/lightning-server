package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningdb.Condition
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.await
import kotlinx.serialization.KSerializer
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*
import kotlin.reflect.KProperty1

class DynamoDbCollection<T: Any>(
    val client: DynamoDbAsyncClient,
    val serializer: KSerializer<T>,
    val tableName: String
): AbstractSignalFieldCollection<T>() {

    override suspend fun find(
        condition: Condition<T>,
        orderBy: List<SortPart<T>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
    ): Flow<T> {
        val c = condition.dynamo(serializer)
        if(c.never) return emptyFlow()
        val builder = DynamoQueryBuilder()
        val filterString = c.writeFilter?.let {
            val filter = builder.Part()
            it.invoke(filter)
            filter.filter.toString()
        }

        val parsed = if(c.writeKey != null) {
            val key = builder.Part()
            c.writeKey.invoke(key)
            client.queryPaginator {
                it.tableName(tableName)
                if(c.local == null) it.limit(limit + skip)
                it.keyConditionExpression(key.filter.toString())
                filterString?.let { f -> it.filterExpression(f) }
                builder.nameMap.takeUnless { it.isEmpty() }?.let { m -> it.expressionAttributeNames(m) }
                builder.valueMap.takeUnless { it.isEmpty() }?.let { m -> it.expressionAttributeValues(m) }
            }.items().parse(serializer)
        } else {
            client.scanPaginator {
                it.tableName(tableName)
                if(c.local == null) it.limit(limit)
                filterString?.let { f -> it.filterExpression(f) }
                builder.nameMap.takeUnless { it.isEmpty() }?.let { m -> it.expressionAttributeNames(m) }
                builder.valueMap.takeUnless { it.isEmpty() }?.let { m -> it.expressionAttributeValues(m) }
            }.items().parse(serializer)
        }
        return c.local?.let { parsed.filter { i -> it(i) } } ?: parsed
    }

    override suspend fun insertImpl(models: List<T>): List<T> {
        client.batchWriteItem {
            it.requestItems(mapOf(tableName to models.map { WriteRequest.builder().putRequest(
                PutRequest.builder().item(serializer.toDynamoMap(it)).build()
            ).build()}))
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

    override suspend fun updateOneImpl(condition: Condition<T>, modification: Modification<T>): EntryChange<T> {
        TODO("Not yet implemented")
    }

    override suspend fun updateOneIgnoringResultImpl(condition: Condition<T>, modification: Modification<T>): Boolean {
        TODO("Not yet implemented")
    }

    override suspend fun updateManyImpl(condition: Condition<T>, modification: Modification<T>): CollectionChanges<T> {
        TODO("Not yet implemented")
    }

    override suspend fun updateManyIgnoringResultImpl(condition: Condition<T>, modification: Modification<T>): Int {
        TODO("Not yet implemented")
    }

    override suspend fun deleteOneImpl(condition: Condition<T>): T? {
        TODO("Not yet implemented")
    }

    override suspend fun deleteOneIgnoringOldImpl(condition: Condition<T>): Boolean {
        TODO("Not yet implemented")
    }

    override suspend fun deleteManyImpl(condition: Condition<T>): List<T> {
        TODO("Not yet implemented")
    }

    override suspend fun deleteManyIgnoringOldImpl(condition: Condition<T>): Int {
        TODO("Not yet implemented")
    }

    override suspend fun count(condition: Condition<T>): Int {
        TODO("Not yet implemented")
    }

    override suspend fun <Key> groupCount(condition: Condition<T>, groupBy: KProperty1<T, Key>): Map<Key, Int> {
        TODO("Not yet implemented")
    }

    override suspend fun <N : Number> aggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        property: KProperty1<T, N>
    ): Double? {
        TODO("Not yet implemented")
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        groupBy: KProperty1<T, Key>,
        property: KProperty1<T, N>
    ): Map<Key, Double?> {
        TODO("Not yet implemented")
    }


}

suspend fun DynamoDbAsyncClient.createOrUpdateIdTable(tableName: String) {
    try {
        describeTable { it.tableName(tableName) }.await()
    } catch(e: Exception) {
        createTable {
            it.tableName(tableName)
            it.billingMode(BillingMode.PAY_PER_REQUEST)
            it.keySchema(KeySchemaElement.builder().attributeName("_id").keyType(KeyType.HASH).build())
            it.attributeDefinitions(AttributeDefinition.builder().attributeName("_id").attributeType(ScalarAttributeType.S).build())
        }.await()
    }
}
