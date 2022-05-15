package com.lightningkite.ktordb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class InMemoryFieldCollection<Model : Any>(val data: MutableList<Model>) : FieldCollection<Model> {
    override suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
    ): Flow<Model> = flow {
        data.asSequence()
            .filter { condition(it) }
            .let {
                orderBy.comparator?.let { c ->
                    it.sortedWith(c)
                } ?: it
            }
            .drop(skip)
            .take(limit)
            .forEach {
                emit(it)
            }
    }

    override suspend fun insertOne(model: Model): Model {
        data.add(model)
        return model
    }

    override suspend fun insertMany(models: List<Model>): List<Model> {
        data.addAll(models)
        return models
    }

    override suspend fun replaceOne(condition: Condition<Model>, model: Model): Model? {
        for (it in data.indices) {
            val old = data[it]
            if(condition(old)) {
                data[it] = model
                return old
            }
        }
        return null
    }

    override suspend fun upsertOne(condition: Condition<Model>, model: Model): Model? {
        for (it in data.indices) {
            val old = data[it]
            if(condition(old)) {
                data[it] = model
                return old
            }
        }
        data.add(model)
        return model
    }

    override suspend fun updateOne(condition: Condition<Model>, modification: Modification<Model>): Boolean {
        for (it in data.indices) {
            val old = data[it]
            if(condition(old)) {
                data[it] = modification(old)
                return true
            }
        }
        return false
    }

    override suspend fun findOneAndUpdate(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): EntryChange<Model> {
        for (it in data.indices) {
            val old = data[it]
            if(condition(old)) {
                val new = modification(old)
                data[it] = new
                return EntryChange(old = old, new = new)
            }
        }
        return EntryChange(null, null)
    }

    override suspend fun updateMany(condition: Condition<Model>, modification: Modification<Model>): Int {
        var counter = 0
        data.indices.forEach {
            val old = data[it]
            if(condition(old)) {
                val new = modification(old)
                data[it] = new
                counter++
            }
        }
        return counter
    }

    override suspend fun deleteOne(condition: Condition<Model>): Boolean {
        for (it in data.indices) {
            val old = data[it]
            if(condition(old)) {
                data.removeAt(it)
                return true
            }
        }
        return false
    }

    override suspend fun deleteMany(condition: Condition<Model>): Int {
        var count = 0
        data.removeAll {
            if(condition(it)) {
                count++
                true
            } else {
                false
            }
        }
        return count
    }

    override suspend fun watch(condition: Condition<Model>): Flow<EntryChange<Model>> = TODO("Not yet implemented")
}

