package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningdb.Condition
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.reactive.asFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.*

class DynamoDbCollection<T : Any>(
    val client: DynamoDbAsyncClient,
    val serializer: KSerializer<T>,
    val tableName: String,
) : AbstractSignalFieldCollection<T>() {

    val idSerializer = serializer.fieldSerializer("_id") as? KSerializer<Any>

    suspend fun findRaw(
        condition: Condition<T>,
        orderBy: List<SortPart<T>> = listOf(),
        skip: Int = 0,
        limit: Int = Int.MAX_VALUE,
        maxQueryMs: Long = 30_000L,
    ): Flow<Pair<Map<String, AttributeValue>, T>> {
        //TODO: Need to use the serial name
        val orderKey = orderBy.map { it.field.toString() }
        val index = indices[orderKey]
        val key = if (index != null) orderKey.first() else "_id"
        val c = condition.dynamo(serializer, key)
        if (c.never) return emptyFlow()
        val parsed = if (c.writeKey != null) {
            client.queryPaginator {
                it.tableName(tableName)
                index?.let { i -> it.indexName(tableName + "_" + i) }
                if (orderBy.firstOrNull()?.ascending == false) it.scanIndexForward(false)
                if (c.local == null) it.limit(limit + skip)
                it.apply(c)
            }.items().map { it to serializer.fromDynamoMap(it) }.asFlow()
        } else {
            client.scanPaginator {
                it.tableName(tableName)
                index?.let { i -> it.indexName(tableName + "_" + i) }
                if (orderBy.firstOrNull()?.ascending == false) throw IllegalArgumentException()
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
        skipFieldsMask: Modification<T>?,
        maxQueryMs: Long,
    ): Flow<T> = findRaw(condition, orderBy, skip, limit, maxQueryMs).map { it.second }

    override suspend fun insertImpl(models: Iterable<T>): List<T> {
        client.batchWriteItem {
            it.requestItems(mapOf(tableName to models.map {
                WriteRequest.builder().putRequest(
                    PutRequest.builder().item(serializer.toDynamoMap(it)).build()
                ).build()
            }))
        }.await()
        return models.toList()
    }

    override suspend fun replaceOneImpl(condition: Condition<T>, model: T, orderBy: List<SortPart<T>>): EntryChange<T> {
        TODO("Not yet implemented")
    }

    override suspend fun replaceOneIgnoringResultImpl(
        condition: Condition<T>,
        model: T,
        orderBy: List<SortPart<T>>,
    ): Boolean {
        TODO("Not yet implemented")
    }

    override suspend fun upsertOneImpl(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T,
    ): EntryChange<T> {
        TODO("Not yet implemented")
    }

    override suspend fun upsertOneIgnoringResultImpl(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T,
    ): Boolean {
        TODO("Not yet implemented")
    }

    suspend fun <R> perKey(
        condition: Condition<T>,
        limit: Int = Int.MAX_VALUE,
        action: suspend (condition: DynamoCondition<T>, key: Map<String, AttributeValue>) -> R,
    ): Flow<R> {
        val c = condition.dynamo(serializer, "_id")
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

    override suspend fun updateOneImpl(condition: Condition<T>, modification: Modification<T>, orderBy: List<SortPart<T>>): EntryChange<T> {
        val m = modification.dynamo(serializer)
        return perKey(condition, limit = 1) { c, key ->
            val result = client.updateItem {
                it.tableName(tableName)
                it.returnValues(ReturnValue.ALL_OLD)
                it.apply(c, m)
                it.key(key)
            }.await()
            val o = serializer.fromDynamoMap(result.attributes())
            EntryChange(
                old = o,
                new = modification(o)
            )
        }.singleOrNull() ?: EntryChange(null, null)
    }

    override suspend fun updateOneIgnoringResultImpl(condition: Condition<T>, modification: Modification<T>, orderBy: List<SortPart<T>>): Boolean {
        val m = modification.dynamo(serializer)
        return perKey(condition, limit = 1) { c, key ->
            client.updateItem {
                it.tableName(tableName)
                it.apply(c, m)
                it.key(key)
            }.await()
            true
        }.singleOrNull() ?: false
    }

    override suspend fun updateManyImpl(condition: Condition<T>, modification: Modification<T>): CollectionChanges<T> {
        val m = modification.dynamo(serializer)
        val changes = ArrayList<EntryChange<T>>()
        perKey(condition) { c, key ->
            val result = client.updateItem {
                it.tableName(tableName)
                it.returnValues(ReturnValue.ALL_OLD)
                it.apply(c, m)
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
        val m = modification.dynamo(serializer)
        perKey(condition) { c, key ->
            val result = client.updateItem {
                it.tableName(tableName)
                it.apply(c, m)
                it.key(key)
            }.await()
            changed++
        }.collect { }
        return changed
    }

    override suspend fun deleteOneImpl(condition: Condition<T>, orderBy: List<SortPart<T>>): T? {
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

    override suspend fun deleteOneIgnoringOldImpl(condition: Condition<T>, orderBy: List<SortPart<T>>): Boolean {
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
        if (condition is Condition.Always)
            return client.describeTable { it.tableName(tableName) }.await().table().itemCount().toInt()
        else {
            val c = condition.dynamo(serializer, "_id")
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

    override suspend fun <Key> groupCount(condition: Condition<T>, groupBy: DataClassPath<T, Key>): Map<Key, Int> {
        val map = HashMap<Key, Int>()
        find(condition).collect {
            val key = groupBy.get(it) ?: return@collect
            map[key] = map.getOrDefault(key, 0)
        }
        return map
    }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        property: DataClassPath<T, N>,
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
        groupBy: DataClassPath<T, Key>,
        property: DataClassPath<T, N>,
    ): Map<Key, Double?> {
        val map = HashMap<Key, Aggregator>()
        find(condition).collect {
            val key = groupBy.get(it) ?: return@collect
            property.get(it)?.toDouble()?.let { map.getOrPut(key) { aggregate.aggregator() }.consume(it) }
        }
        return map.mapValues { it.value.complete() }
    }

    private var prepared = false
    private lateinit var indices: Map<List<String>, String>

    @OptIn(ExperimentalSerializationApi::class)
    internal suspend fun prepare() {
        if (prepared) return
        val expectedIndices = HashMap<String, List<String>>()
        val seen = HashSet<SerialDescriptor>()
        fun handleDescriptor(descriptor: SerialDescriptor) {
            if (!seen.add(descriptor)) return
            descriptor.annotations.forEach {
                when (it) {
                    is UniqueSet -> expectedIndices[it.fields.joinToString("_")] = it.fields.toList()

                    is UniqueSetJankPatch -> {
                        val sets: MutableList<MutableList<String>> = mutableListOf()
                        var current = mutableListOf<String>()
                        it.fields.forEach { value ->
                            if (value == ":") {
                                sets.add(current)
                                current = mutableListOf()
                            } else {
                                current.add(value)
                            }
                        }
                        sets.add(current)
                        sets.forEach { set ->
                            expectedIndices[set.joinToString("_")] = set.toList()
                        }
                    }

                    is IndexSet -> expectedIndices[it.fields.joinToString("_")] = it.fields.toList()

                    is IndexSetJankPatch -> {
                        val sets: MutableList<MutableList<String>> = mutableListOf()
                        var current = mutableListOf<String>()
                        it.fields.forEach { value ->
                            if (value == ":") {
                                sets.add(current)
                                current = mutableListOf()
                            } else {
                                current.add(value)
                            }
                        }
                        sets.add(current)
                        sets.forEach { set ->
                            expectedIndices[set.joinToString("_")] = set.toList()
                        }
                    }

                    is TextIndex -> throw IllegalArgumentException()
                    is NamedUniqueSet -> expectedIndices[it.indexName] = it.fields.toList()

                    is NamedUniqueSetJankPatch -> {
                        val sets: MutableList<MutableList<String>> = mutableListOf()
                        var current = mutableListOf<String>()
                        it.fields.forEach { value ->
                            if (value == ":") {
                                sets.add(current)
                                current = mutableListOf()
                            } else {
                                current.add(value)
                            }
                        }
                        sets.add(current)
                        val names = it.indexNames.split(":").map { it.trim() }

                        sets.forEachIndexed { index, set ->
                            expectedIndices[names.getOrNull(index) ?: set.joinToString("_")] = set.toList()
                        }
                    }

                    is NamedIndexSet -> expectedIndices[it.indexName] = it.fields.toList()

                    is NamedIndexSetJankPatch -> {

                        val sets: MutableList<MutableList<String>> = mutableListOf()
                        var current = mutableListOf<String>()
                        it.fields.forEach { value ->
                            if (value == ":") {
                                sets.add(current)
                                current = mutableListOf()
                            } else {
                                current.add(value)
                            }
                        }
                        sets.add(current)
                        val names = it.indexNames.split(":").map { it.trim() }

                        sets.forEachIndexed { index, set ->
                            expectedIndices[names.getOrNull(index) ?: set.joinToString("_")] = set.toList()
                        }

                    }
                }
            }
            (0 until descriptor.elementsCount).forEach { index ->
                val sub = descriptor.getElementDescriptor(index)
                if (sub.kind == StructureKind.CLASS) handleDescriptor(sub)
                descriptor.getElementAnnotations(index).forEach {
                    when (it) {
                        is NamedIndex -> expectedIndices[it.indexName] = listOf(descriptor.getElementName(index))
                        is Index -> expectedIndices[descriptor.getElementName(index)] =
                            listOf(descriptor.getElementName(index))

                        is NamedUnique -> expectedIndices[it.indexName] = listOf(descriptor.getElementName(index))
                        is Unique -> expectedIndices[descriptor.getElementName(index)] =
                            listOf(descriptor.getElementName(index))
                    }
                }
            }
        }
        handleDescriptor(serializer.descriptor)

        try {
            val described = client.describeTable { it.tableName(tableName) }.await()
            if (described.table().hasGlobalSecondaryIndexes()) described.table().globalSecondaryIndexes() else listOf()
            //TODO: Update the table as needed
        } catch (e: Exception) {
            client.createTable {
                it.tableName(tableName)
                it.billingMode(BillingMode.PAY_PER_REQUEST)
                it.keySchema(KeySchemaElement.builder().attributeName("_id").keyType(KeyType.HASH).build())
                val k = expectedIndices.values.flatten().toSet() + "_id"
                it.attributeDefinitions((0 until serializer.descriptor.elementsCount).filter {
                    serializer.descriptor.getElementName(
                        it
                    ) in k
                }.mapNotNull { index ->
                    serializer.descriptor.getElementDescriptor(index).dynamoType().scalar()?.let { t ->
                        AttributeDefinition.builder().attributeName(serializer.descriptor.getElementName(index))
                            .attributeType(t).build()
                    }
                })
                it.globalSecondaryIndexes(expectedIndices.map {
                    when (it.value.size) {
                        1 -> GlobalSecondaryIndex.builder()
                            .indexName(tableName + "_" + it.key)
                            .keySchema(
                                KeySchemaElement.builder()
                                    .attributeName(it.value[0])
                                    .keyType(KeyType.HASH)
                                    .build()
                            )
                            .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                            .build()

                        2 -> GlobalSecondaryIndex.builder()
                            .indexName(tableName + "_" + it.key)
                            .keySchema(
                                KeySchemaElement.builder()
                                    .attributeName(it.value[0])
                                    .keyType(KeyType.HASH)
                                    .build(),
                                KeySchemaElement.builder()
                                    .attributeName(it.value[1])
                                    .keyType(KeyType.RANGE)
                                    .build()
                            )
                            .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                            .build()

                        else -> throw IllegalArgumentException("")
                    }
                })
            }.await()
            listOf()
        }
        indices = expectedIndices.entries.associate { it.value to it.key }
        prepared = true
    }
}

fun Condition<*>.singleValue(): Any? = when (this) {
    is Condition.Equal -> this.value
    is Condition.And -> this.conditions.asSequence().mapNotNull { it.singleValue() }.firstOrNull()
    else -> null
}

fun Condition<*>.exactPrimaryKey(): Any? {
    return when (this) {
        is Condition.And -> this.conditions.asSequence().mapNotNull { it.exactPrimaryKey() }.firstOrNull()
        is Condition.OnField<*, *> -> if (this.key.name == "_id") this.condition.singleValue() else null
        else -> null
    }
}

