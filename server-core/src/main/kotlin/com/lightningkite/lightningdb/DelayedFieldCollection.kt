package com.lightningkite.lightningdb

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.KSerializer
import com.lightningkite.lightningdb.SerializableProperty
import kotlin.reflect.KType

/**
 * Wraps a FieldCollection with the sole purpose of adding a delay in the response to every call.
 * @param wraps The actual underlying FieldCollection to retrieve data from.
 * @param milliseconds The amount of delay that will be added to every call.
 */
open class DelayedFieldCollection<Model : Any>(
    override val wraps: FieldCollection<Model>,
    val milliseconds: Long
) : FieldCollection<Model> {
    override val serializer: KSerializer<Model> get() = wraps.serializer
    override suspend fun fullCondition(condition: Condition<Model>): Condition<Model> = wraps.fullCondition(condition)
    override suspend fun mask(): Mask<Model> = wraps.mask()
    override suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<Model> = wraps.find(condition, orderBy, skip, limit, maxQueryMs).onStart { delay(milliseconds) }

    override suspend fun findPartial(
        fields: Set<DataClassPathPartial<Model>>,
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
    ): Flow<Partial<Model>> =
        wraps.findPartial(fields, condition, orderBy, skip, limit, maxQueryMs).onStart { delay(milliseconds) }

    override suspend fun count(condition: Condition<Model>): Int {
        delay(milliseconds)
        return wraps.count(condition)
    }

    override suspend fun <Key> groupCount(condition: Condition<Model>, groupBy: DataClassPath<Model, Key>): Map<Key, Int> {
        delay(milliseconds)
        return wraps.groupCount(condition, groupBy)
    }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: DataClassPath<Model, N>,
    ): Double? {
        delay(milliseconds)
        return wraps.aggregate(aggregate, condition, property)
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>,
        property: DataClassPath<Model, N>,
    ): Map<Key, Double?> {
        delay(milliseconds)
        return wraps.groupAggregate(aggregate, condition, groupBy, property)
    }

    override suspend fun insert(models: Iterable<Model>): List<Model> {
        delay(milliseconds)
        return wraps.insert(models)
    }

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>,
    ): EntryChange<Model> {
        delay(milliseconds)
        return wraps.replaceOne(condition, model, orderBy)
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>,
    ): Boolean {
        delay(milliseconds)
        return wraps.replaceOneIgnoringResult(condition, model, orderBy)
    }

    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model,
    ): EntryChange<Model> {
        delay(milliseconds)
        return wraps.upsertOne(condition, modification, model)
    }

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model,
    ): Boolean {
        delay(milliseconds)
        return wraps.upsertOneIgnoringResult(condition, modification, model)
    }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>,
    ): EntryChange<Model> {
        delay(milliseconds)
        return wraps.updateOne(condition, modification, orderBy)
    }

    override suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>,
    ): Boolean {
        delay(milliseconds)
        return wraps.updateOneIgnoringResult(condition, modification, orderBy)
    }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): CollectionChanges<Model> {
        delay(milliseconds)
        return wraps.updateMany(condition, modification)
    }

    override suspend fun updateManyIgnoringResult(condition: Condition<Model>, modification: Modification<Model>): Int {
        delay(milliseconds)
        return wraps.updateManyIgnoringResult(condition, modification)
    }

    override suspend fun deleteOne(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? {
        delay(milliseconds)
        return wraps.deleteOne(condition, orderBy)
    }

    override suspend fun deleteOneIgnoringOld(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Boolean {
        delay(milliseconds)
        return wraps.deleteOneIgnoringOld(condition, orderBy)
    }

    override suspend fun deleteMany(condition: Condition<Model>): List<Model> {
        delay(milliseconds)
        return wraps.deleteMany(condition)
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int {
        delay(milliseconds)
        return wraps.deleteManyIgnoringOld(condition)
    }

    override fun registerRawSignal(callback: suspend (CollectionChanges<Model>) -> Unit) {
        return wraps.registerRawSignal(callback)
    }
}

fun <Model : Any> FieldCollection<Model>.delayed(milliseconds: Long): FieldCollection<Model> =
    DelayedFieldCollection(this, milliseconds)

fun Database.delayed(milliseconds: Long): Database = object : Database {
    override fun <T : Any> collection(module: SerializersModule, serializer: KSerializer<T>, name: String): FieldCollection<T> {
        return this@delayed.collection<T>(serializer, name).delayed(milliseconds)
    }

    override suspend fun connect() = this@delayed.connect()
    override suspend fun disconnect() = this@delayed.disconnect()
}
