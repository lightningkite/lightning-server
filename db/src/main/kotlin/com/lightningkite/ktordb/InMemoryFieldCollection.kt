package com.lightningkite.ktordb

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KProperty1
import kotlin.reflect.KType

open class InMemoryFieldCollection<Model : Any>(val data: MutableList<Model> = ArrayList()) : FieldCollection<Model> {

    private val lock = ReentrantLock()
    val changeChannel = MutableSharedFlow<EntryChange<Model>>()

    override suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
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

    override suspend fun insertOne(model: Model): Model = lock.withLock {
        data.add(model)
        changeChannel.tryEmit(EntryChange(null, model))
        return model
    }

    override suspend fun insertMany(models: List<Model>): List<Model> = lock.withLock {
        data.addAll(models)
        models.forEach {
            changeChannel.tryEmit(EntryChange(null, it))
        }
        return models
    }

    override suspend fun replaceOne(condition: Condition<Model>, model: Model): Model? = lock.withLock {
        for (it in data.indices) {
            val old = data[it]
            if(condition(old)) {
                data[it] = model
                changeChannel.tryEmit(EntryChange(old, model))
                return old
            }
        }
        return null
    }

    override suspend fun upsertOne(condition: Condition<Model>, model: Model): Model? = lock.withLock {
        for (it in data.indices) {
            val old = data[it]
            if(condition(old)) {
                data[it] = model
                changeChannel.tryEmit(EntryChange(old, model))
                return old
            }
        }
        data.add(model)
        changeChannel.tryEmit(EntryChange(null, model))
        return model
    }

    override suspend fun updateOne(condition: Condition<Model>, modification: Modification<Model>): Boolean = lock.withLock {
        for (it in data.indices) {
            val old = data[it]
            if(condition(old)) {
                val new = modification(old)
                data[it] = new
                changeChannel.tryEmit(EntryChange(old, new))
                return true
            }
        }
        return false
    }

    override suspend fun findOneAndUpdate(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): EntryChange<Model> = lock.withLock {
        for (it in data.indices) {
            val old = data[it]
            if(condition(old)) {
                val new = modification(old)
                data[it] = new
                val change = EntryChange(old, new)
                changeChannel.tryEmit(change)
                return change
            }
        }
        return EntryChange(null, null)
    }

    override suspend fun updateMany(condition: Condition<Model>, modification: Modification<Model>): Int = lock.withLock {
        var counter = 0
        data.indices.forEach {
            val old = data[it]
            if(condition(old)) {
                val new = modification(old)
                data[it] = new
                changeChannel.tryEmit(EntryChange(old, new))
                counter++
            }
        }
        return counter
    }

    override suspend fun deleteOne(condition: Condition<Model>): Boolean = lock.withLock {
        for (it in data.indices) {
            val old = data[it]
            if(condition(old)) {
                data.removeAt(it)
                changeChannel.tryEmit(EntryChange(old, null))
                return true
            }
        }
        return false
    }

    override suspend fun deleteMany(condition: Condition<Model>): Int = lock.withLock {
        var count = 0
        data.removeAll {
            if(condition(it)) {
                changeChannel.tryEmit(EntryChange(it, null))
                count++
                true
            } else {
                false
            }
        }
        return count
    }

    override suspend fun watch(condition: Condition<Model>): Flow<EntryChange<Model>> = changeChannel.onEach { delay(1L) }

    override suspend fun count(condition: Condition<Model>): Int = data.count { condition(it) }

    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: KProperty1<Model, Key>,
    ): Map<Key, Int> = data.groupingBy { groupBy.get(it) }.eachCount()

    override suspend fun <N : Number> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: KProperty1<Model, N>
    ): Double? = data.asSequence().map { property.get(it).toDouble() }.aggregate(aggregate)

    override suspend fun <N: Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        groupBy: KProperty1<Model, Key>,
        property: KProperty1<Model, N>,
    ): Map<Key, Double?> = data.asSequence().mapNotNull { groupBy.get(it) to (property.get(it)?.toDouble() ?: return@mapNotNull null) }.aggregate(aggregate)
}

