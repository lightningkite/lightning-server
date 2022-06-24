package com.lightningkite.ktordb

import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KProperty1
import kotlin.reflect.KType

interface FieldCollection<Model: Any> {
    data class DataAndResult<Data, Result>(val data: Data, val result: Result) {
        inline fun onData(action: (Data) -> Data) = copy(data = action(data))
    }

    suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>> = listOf(),
        skip: Int = 0,
        limit: Int = Int.MAX_VALUE,
        maxQueryMs: Long = 15_000
    ): Flow<Model>

    suspend fun insertOne(
        model: Model
    ): Model

    suspend fun insertMany(
        models: List<Model>
    ): List<Model>

    suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model
    ): Model?

    suspend fun upsertOne(
        condition: Condition<Model>,
        model: Model
    ): Model?

    suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): Boolean

    suspend fun findOneAndUpdate(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): EntryChange<Model>

    suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): Int

    suspend fun deleteOne(
        condition: Condition<Model>
    ): Boolean

    suspend fun deleteMany(
        condition: Condition<Model>
    ): Int

    suspend fun watch(
        condition: Condition<Model>
    ): Flow<EntryChange<Model>>

    suspend fun count(
        condition: Condition<Model> = Condition.Always()
    ): Int

    suspend fun <Key> groupCount(
        condition: Condition<Model> = Condition.Always(),
        groupBy: KProperty1<Model, Key>
    ): Map<Key, Int>

    suspend fun <N: Number> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model> = Condition.Always(),
        property: KProperty1<Model, N>
    ): Double?

    suspend fun <N: Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model> = Condition.Always(),
        groupBy: KProperty1<Model, Key>,
        property: KProperty1<Model, N>
    ): Map<Key, Double?>

}
