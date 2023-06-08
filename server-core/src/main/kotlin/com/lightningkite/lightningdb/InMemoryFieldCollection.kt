package com.lightningkite.lightningdb

import com.lightningkite.lightningserver.exceptions.BadRequestException
import io.ktor.util.reflect.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A FieldCollection who's underlying implementation is actually manipulating a MutableList.
 * This is useful for times that an actual database is not needed, and you need to move fast, such as during Unit Tests.
 */
open class InMemoryFieldCollection<Model : Any>(
    val data: MutableList<Model> = ArrayList(),
    val serializer: KSerializer<Model>
) :
    AbstractSignalFieldCollection<Model>() {

    private val lock = ReentrantLock()

    private val uniqueIndexChecks = ConcurrentLinkedQueue<(List<EntryChange<Model>>) -> Unit>()

    private fun uniqueCheck(changed: EntryChange<Model>) = uniqueCheck(listOf(changed))
    private fun uniqueCheck(changes: List<EntryChange<Model>>) = uniqueIndexChecks.forEach { it(changes) }

    init {
        serializer.descriptor.indexes().forEach { index: NeededIndex ->
            if (index.unique) {
                val fields =
                    serializer.attemptGrabFields().filterKeys { index.fields.contains(it) }.values
                uniqueIndexChecks.add { changes: List<EntryChange<Model>> ->
                    val fieldChanges = changes.mapNotNull { entryChange ->
                        if (
                            (entryChange.old == null && entryChange.new != null) ||
                            (entryChange.old != null &&
                                    entryChange.new != null &&
                                    fields.any { it.get(entryChange.old!!) != it.get(entryChange.new!!) })
                        )
                            fields.map { it to it.get(entryChange.new!!) }
                        else
                            null
                    }
                    fieldChanges.forEach { fieldValues ->
                        if (data.any { fromDb -> fieldValues.all { (property, value) -> property.get(fromDb) == value } }) {
                            throw BadRequestException("Unique Index Violation. The following fields are already in the database: ${fieldValues.joinToString { (property, value) -> "${property.name}: $value" }}")
                        }
                    }
                }
            }
        }
    }

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
        uniqueCheck(models.map { EntryChange(null, it) })
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
                val changed = EntryChange(old, model)
                uniqueCheck(changed)
                data[it] = model
                return changed
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
                val new = modification(old)
                val changed = EntryChange(old, new)
                uniqueCheck(changed)
                data[it] = new
                return changed
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
                val changed = EntryChange(old, new)
                uniqueCheck(changed)
                data[it] = new
                return changed
            }
        }
        return EntryChange(null, null)
    }

    override suspend fun updateManyImpl(
        condition: Condition<Model>,
        modification: Modification<Model>,
    ): CollectionChanges<Model> = lock.withLock {
        return data.indices
            .mapNotNull {
                val old = data[it]
                if (condition(old)) {
                    val new = modification(old)
                    it to EntryChange(old, new)
                } else null
            }
            .let {
                val changes = it.map { it.second }
                uniqueCheck(changes)
                it.forEach { (index, change) -> data[index] = change.new!! }
                CollectionChanges(changes = changes)
            }
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

