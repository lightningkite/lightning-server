package com.lightningkite.lightningdb

import java.util.concurrent.ConcurrentLinkedQueue

abstract class AbstractSignalFieldCollection<Model : Any> : FieldCollection<Model> {

    val signals = ConcurrentLinkedQueue<suspend (CollectionChanges<Model>) -> Unit>()
    override fun registerRawSignal(callback: suspend (CollectionChanges<Model>) -> Unit) {
        signals.add(callback)
    }

    private suspend fun signal(change: CollectionChanges<Model>) {
        signals.forEach { it(change) }
    }

    final override suspend fun insert(models: Iterable<Model>): List<Model> {
        val result = insertImpl(models)
        val change = CollectionChanges(changes = result.map { EntryChange(new = it) })
        signal(change)
        return result
    }

    final override suspend fun deleteMany(condition: Condition<Model>): List<Model> {
        return deleteManyImpl(condition).also {
            val change = CollectionChanges(changes = it.map { EntryChange(old = it) })
            signal(change)
        }
    }

    final override suspend fun deleteOne(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? {
        return deleteOneImpl(condition, orderBy)?.also {
            val change = CollectionChanges(old = it)
            signal(change)
        }
    }

    final override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> =
        replaceOneImpl(condition, model, orderBy).also {
            if (it.new == null) return@also
            if (it.new == it.old) return@also
            val change = CollectionChanges(it.old, it.new)
            signal(change)
        }

    final override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> =
        updateOneImpl(condition, modification, orderBy).also {
            if (it.new == null) return@also
            if (it.new == it.old) return@also
            val change = CollectionChanges(it.old, it.new)
            signal(change)
        }

    final override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model> = upsertOneImpl(condition, modification, model).also {
        val change = CollectionChanges(it.old, it.new)
        if (it.new == it.old) return@also
        signal(change)
    }

    final override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): CollectionChanges<Model> = updateManyImpl(condition, modification).also { changes ->
        signal(CollectionChanges(changes.changes.filter { it.old != it.new }))
    }


    final override suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): Boolean =
        if (signals.isEmpty()) replaceOneIgnoringResultImpl(condition, model, orderBy)
        else replaceOne(condition, model, orderBy).new != null

    final override suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): Boolean =
        if (signals.isEmpty()) upsertOneIgnoringResultImpl(condition, modification, model)
        else upsertOne(condition, modification, model).old != null

    final override suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean =
        if (signals.isEmpty()) updateOneIgnoringResultImpl(condition, modification, orderBy)
        else updateOne(condition, modification, orderBy).new != null

    final override suspend fun updateManyIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): Int =
        if (signals.isEmpty()) updateManyIgnoringResultImpl(condition, modification)
        else updateMany(condition, modification).changes.size

    final override suspend fun deleteOneIgnoringOld(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean =
        if (signals.isEmpty()) deleteOneIgnoringOldImpl(condition, orderBy)
        else deleteOne(condition, orderBy) != null

    final override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int =
        if (signals.isEmpty()) deleteManyIgnoringOldImpl(condition)
        else deleteMany(condition).size

    protected abstract suspend fun insertImpl(models: Iterable<Model>): List<Model>
    protected abstract suspend fun replaceOneImpl(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>> = listOf()
    ): EntryChange<Model>

    protected abstract suspend fun replaceOneIgnoringResultImpl(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>> = listOf()
    ): Boolean

    protected abstract suspend fun upsertOneImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model>

    protected abstract suspend fun upsertOneIgnoringResultImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): Boolean

    protected abstract suspend fun updateOneImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>> = listOf()
    ): EntryChange<Model>

    protected abstract suspend fun updateOneIgnoringResultImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean

    protected abstract suspend fun updateManyImpl(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): CollectionChanges<Model>

    protected abstract suspend fun updateManyIgnoringResultImpl(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): Int

    protected abstract suspend fun deleteOneImpl(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model?
    protected abstract suspend fun deleteOneIgnoringOldImpl(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>> = listOf()
    ): Boolean

    protected abstract suspend fun deleteManyImpl(condition: Condition<Model>): List<Model>
    protected abstract suspend fun deleteManyIgnoringOldImpl(condition: Condition<Model>): Int
}