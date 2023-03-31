package com.lightningkite.lightningdb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A FieldCollection who's underlying implementation is actually manipulating a MutableList.
 * This is useful for times that an actual database is not needed, and you need to move fast, such as during Unit Tests.
 */
open class InMemoryFieldCollection<Model : Any>(val data: MutableList<Model> = ArrayList()) :
    AbstractSignalFieldCollection<Model>() {

    private val lock = ReentrantLock()

    override suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<Model> = flow {
        val result = lock.withLock {
            data.asSequence()
                .filter { condition(it) }
                .let {
                    orderBy.comparator?.let { c ->
                        it.sortedWith(c)
                    } ?: it
                }
                .drop(skip)
                .take(limit)
                .toList()
        }
        result
            .forEach {
                emit(it)
            }
    }

    override suspend fun count(condition: Condition<Model>): Int = data.count { condition(it) }

    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: KeyPath<Model, Key>,
    ): Map<Key, Int> = data.groupingBy { groupBy.get(it) }.eachCount()

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: KeyPath<Model, N>,
    ): Double? =
        data.asSequence().filter { condition(it) }.mapNotNull { property.get(it)?.toDouble() }.aggregate(aggregate)

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        groupBy: KeyPath<Model, Key>,
        property: KeyPath<Model, N>,
    ): Map<Key, Double?> = data.asSequence().filter { condition(it) }
        .mapNotNull { groupBy.get(it) to (property.get(it)?.toDouble() ?: return@mapNotNull null) }.aggregate(aggregate)

    override suspend fun insertImpl(models: Iterable<Model>): List<Model> = lock.withLock {
        data.addAll(models)
        return models.toList()
    }

    override suspend fun replaceOneImpl(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>,
    ): EntryChange<Model> = lock.withLock {
        for (it in sortIndices(orderBy)) {
            val old = data[it]
            if (condition(old)) {
                data[it] = model
                return EntryChange(old, model)
            }
        }
        return EntryChange(null, null)
    }

    private fun sortIndices(orderBy: List<SortPart<Model>>): Iterable<Int> {
        return data.indices.let {
            orderBy.comparator?.let { c ->
                it.sortedWith { a, b -> c.compare(data[a], data[b]) }
            } ?: it
        }
    }

    override suspend fun upsertOneImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model,
    ): EntryChange<Model> = lock.withLock {
        for (it in data.indices) {
            val old = data[it]
            if (condition(old)) {
                val changed = modification(old)
                data[it] = changed
                return EntryChange(old, changed)
            }
        }
        data.add(model)
        return EntryChange(null, model)
    }

    override suspend fun updateOneImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>,
    ): EntryChange<Model> = lock.withLock {
        for (it in sortIndices(orderBy)) {
            val old = data[it]
            if (condition(old)) {
                val new = modification(old)
                data[it] = new
                return EntryChange(old, new)
            }
        }
        return EntryChange(null, null)
    }

    override suspend fun updateManyImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): CollectionChanges<Model> = lock.withLock {
        val changes = ArrayList<EntryChange<Model>>()
        var counter = 0
        data.indices.forEach {
            val old = data[it]
            if (condition(old)) {
                val new = modification(old)
                data[it] = new
                changes.add(EntryChange(old, new))
                counter++
            }
        }
        return CollectionChanges(changes = changes)
    }

    override suspend fun deleteOneImpl(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? =
        lock.withLock {
            for (it in sortIndices(orderBy)) {
                val old = data[it]
                if (condition(old)) {
                    data.removeAt(it)
                    return old
                }
            }
            return null
        }

    override suspend fun deleteManyImpl(condition: Condition<Model>): List<Model> = lock.withLock {
        val removed = ArrayList<Model>()
        data.removeAll {
            if (condition(it)) {
                removed.add(it)
                true
            } else {
                false
            }
        }
        return removed
    }

    override suspend fun replaceOneIgnoringResultImpl(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>,
    ): Boolean = replaceOne(
        condition,
        model,
        orderBy
    ).new != null

    override suspend fun upsertOneIgnoringResultImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model,
    ): Boolean = upsertOne(condition, modification, model).old != null

    override suspend fun updateOneIgnoringResultImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>,
    ): Boolean = updateOne(condition, modification, orderBy).new != null

    override suspend fun updateManyIgnoringResultImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): Int = updateMany(condition, modification).changes.size

    override suspend fun deleteOneIgnoringOldImpl(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
    ): Boolean = deleteOne(condition, orderBy) != null

    override suspend fun deleteManyIgnoringOldImpl(condition: Condition<Model>): Int = deleteMany(condition).size

    fun drop() {
        data.clear()
    }
}

