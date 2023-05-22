package com.lightningkite.lightningdb

import kotlinx.coroutines.flow.Flow
import kotlin.reflect.KProperty1

/**
 * An abstract way to communicate with a database on a specific collection/table
 * using conditions and modifications. The underlying database is irrelevant and
 * will have it's own implementation of this interface.
 */
interface FieldCollection<Model : Any> {
    /**
     * The field collection this wraps, if any.
     */
    val wraps: FieldCollection<Model>? get() = null

    /**
     * The full condition that will be sent to the database in the end.  Used to help analyze security rules.
     */
    suspend fun fullCondition(condition: Condition<Model>): Condition<Model> = condition

    /**
     * The mask that will be used on data coming out of the database.  Used to help analyze security rules.
     */
    suspend fun mask(): Mask<Model> = Mask()

    /**
     * Query for items in the collection.
     */
    suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>> = listOf(),
        skip: Int = 0,
        limit: Int = Int.MAX_VALUE,
        maxQueryMs: Long = 15_000
    ): Flow<Model>

    /**
     * Count the number of matching items in the collection.
     */
    suspend fun count(
        condition: Condition<Model> = Condition.Always()
    ): Int

    /**
     * Count the number of matching items in each group.
     */
    suspend fun <Key> groupCount(
        condition: Condition<Model> = Condition.Always(),
        groupBy: KProperty1<Model, Key>
    ): Map<Key, Int>

    /**
     * Aggregate a particular numerical field on all matching items.
     */
    suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model> = Condition.Always(),
        property: KProperty1<Model, N>
    ): Double?

    /**
     * Aggregate a particular numerical field on all matching items by group.
     */
    suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model> = Condition.Always(),
        groupBy: KProperty1<Model, Key>,
        property: KProperty1<Model, N>
    ): Map<Key, Double?>


    /**
     * Insert items into the collection.
     * @return The items that were actually inserted in the end.
     */
    suspend fun insert(
        models: Iterable<Model>
    ): List<Model>


    /**
     * Replaces a single item via a condition.
     * @return The old and new items.
     */
    suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>> = listOf()
    ): EntryChange<Model>

    /**
     * Replaces a single item via a condition.
     * @return If a change was made to the database.
     */
    suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>> = listOf()
    ): Boolean

    /**
     * Inserts an item if it doesn't exist, but otherwise modifies it.
     * @return The old and new items.
     */
    suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model>

    /**
     * Inserts an item if it doesn't exist, but otherwise modifies it.
     * @return If there was an existing element that matched the condition.
     */
    suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): Boolean

    /**
     * Updates a single item in the collection.
     * @return The old and new items.
     */
    suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>> = listOf(),
    ): EntryChange<Model>

    /**
     * Updates a single item in the collection.
     * @return If a change was made to the database.
     */
    suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>> = listOf()
    ): Boolean


    /**
     * Updates many items in the collection.
     * @return The changes made to the collection.
     */
    suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): CollectionChanges<Model>

    /**
     * Updates many items in the collection.
     * @return The number of entries affected.
     */
    suspend fun updateManyIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): Int

    /**
     * Deletes a single item from the collection.
     * @return The item removed from the collection.
     */
    suspend fun deleteOne(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>> = listOf()
    ): Model?

    /**
     * Deletes a single item from the collection.
     * @return Whether any items were deleted.
     */
    suspend fun deleteOneIgnoringOld(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>> = listOf()
    ): Boolean


    /**
     * Deletes many items from the collection.
     * @return The item removed from the collection.
     */
    suspend fun deleteMany(
        condition: Condition<Model>
    ): List<Model>

    /**
     * Deletes many items from the collection.
     * @return The number of deleted items.
     */
    suspend fun deleteManyIgnoringOld(
        condition: Condition<Model>
    ): Int

    /**
     * Registers a raw signal for the collection.
     * This skips over any security rules and goes straight to the root of the database.
     * Useful for handling change signals that absolutely cannot be skipped under any circumstances.
     * Currently only used for websocket change watching.
     */
    fun registerRawSignal(callback: suspend (CollectionChanges<Model>) -> Unit)
}
