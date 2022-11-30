package com.lightningkite.lightningdb

import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KProperty1

interface FieldCollection<Model: Any> {
    val wraps: FieldCollection<Model>? get() = null
    suspend fun fullCondition(condition: Condition<Model>): Condition<Model> = condition
    suspend fun mask(): Mask<Model> = Mask()

    suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>> = listOf(),
        skip: Int = 0,
        limit: Int = Int.MAX_VALUE,
        maxQueryMs: Long = 15_000
    ): Flow<Model>

    suspend fun count(
        condition: Condition<Model> = Condition.Always()
    ): Int

    suspend fun <Key> groupCount(
        condition: Condition<Model> = Condition.Always(),
        groupBy: KProperty1<Model, Key>
    ): Map<Key, Int>

    suspend fun <N: Number?> aggregate(
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


    suspend fun insert(
        models: Iterable<Model>
    ): List<Model>


    suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model
    ): EntryChange<Model>

    /**
     * @return If a change was made to the database.
     */
    suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model
    ): Boolean

    suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model>

    /**
     * @return If there was an existing element that matched the condition.
     */
    suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): Boolean


    suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): EntryChange<Model>

    /**
     * @return If a change was made to the database.
     */
    suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): Boolean


    suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): CollectionChanges<Model>

    /**
     * @return The number of entries affected.
     */
    suspend fun updateManyIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): Int


    suspend fun deleteOne(
        condition: Condition<Model>
    ): Model?

    /**
     * @return Whether any items were deleted.
     */
    suspend fun deleteOneIgnoringOld(
        condition: Condition<Model>
    ): Boolean


    suspend fun deleteMany(
        condition: Condition<Model>
    ): List<Model>

    /**
     * @return The number of entries affected.
     */
    suspend fun deleteManyIgnoringOld(
        condition: Condition<Model>
    ): Int

    fun registerRawSignal(callback: suspend (CollectionChanges<Model>)->Unit)
}
