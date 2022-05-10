package com.lightningkite.ktordb

import kotlinx.coroutines.flow.*


class PostCreateSignalWatchableFieldCollection<Model : Any>(
    val wraps: WatchableFieldCollection<Model>,
    val onCreate: suspend (Model) -> Unit,
) : WatchableFieldCollection<Model> by wraps {
    override suspend fun insertOne(model: Model): Model {
        val result = wraps.insertOne(model)
        onCreate(result)
        return result
    }

    override suspend fun insertMany(models: List<Model>): List<Model> {
        val result = wraps.insertMany(models)
        result.forEach { onCreate(it) }
        return result
    }
}

class PreCreateSignalWatchableFieldCollection<Model : Any>(
    val wraps: WatchableFieldCollection<Model>,
    val onCreate: suspend (Model) -> Model,
) : WatchableFieldCollection<Model> by wraps {
    override suspend fun insertOne(model: Model): Model {
        return wraps.insertOne(onCreate(model))
    }

    override suspend fun insertMany(models: List<Model>): List<Model> {
        return wraps.insertMany(models.map { onCreate(it) })
    }
}

class PreDeleteSignalWatchableFieldCollection<Model : Any>(
    val wraps: WatchableFieldCollection<Model>,
    val onDelete: suspend (Model) -> Unit,
) : WatchableFieldCollection<Model> by wraps {
    override suspend fun deleteMany(condition: Condition<Model>): Int {
        wraps.find(condition).collect(FlowCollector(onDelete))
        return wraps.deleteMany(condition)
    }

    override suspend fun deleteOne(condition: Condition<Model>): Boolean {
        wraps.find(condition, limit = 1).collect(FlowCollector(onDelete))
        return wraps.deleteOne(condition)
    }
}


class PostDeleteSignalWatchableFieldCollection<Model : HasId>(
    val wraps: WatchableFieldCollection<Model>,
    val onDelete: suspend (Model) -> Unit,
) : WatchableFieldCollection<Model> by wraps {
    override suspend fun deleteMany(condition: Condition<Model>): Int {
        var count = 0
        wraps.find(condition).collectChunked(1000) { list ->
            count += wraps.deleteMany(startChain<Model>()[HasIdFields._id<Model>()] inside list.map { it._id })
            list.forEach { onDelete(it) }
        }
        return count
    }

    override suspend fun deleteOne(condition: Condition<Model>): Boolean {
        val toDelete = wraps.find(condition, limit = 1).toList()
        val result = wraps.deleteOne(startChain<Model>()[HasIdFields._id<Model>()] inside toDelete.map { it._id })
        toDelete.forEach { onDelete(it) }
        return result
    }
}

class PostChangeSignalWatchableFieldCollection<Model : HasId>(
    val wraps: WatchableFieldCollection<Model>,
    val changed: suspend (before: Model, after: Model) -> Unit,
) : WatchableFieldCollection<Model> by wraps {
    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model
    ): Model? {
        val before = wraps.find(condition, limit = 1).firstOrNull() ?: return null
        val result = wraps.replaceOne(condition, model)
        if (result != null) changed(before, result)
        return result
    }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): Boolean {
        return findOneAndUpdate(condition, modification).new != null
    }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): Int {
        var count = 0
        wraps.find(condition).collectChunked(1000) { list ->
            count += wraps.updateMany(
                startChain<Model>()[HasIdFields._id<Model>()] inside list.map { it._id },
                modification
            )
            list.forEach { changed(it, modification(it)) }
        }
        return count
    }

    override suspend fun findOneAndUpdate(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): EntryChange<Model> {
        val change = wraps.findOneAndUpdate(condition, modification)
        if (change.old != null && change.new != null)
            changed(change.old!!, change.new!!)
        return change
    }
}


fun <Model : Any> WatchableFieldCollection<Model>.postWatchableCreate(
    action: suspend (Model) -> Unit
): WatchableFieldCollection<Model> = PostCreateSignalWatchableFieldCollection(this, action)

fun <Model : Any> WatchableFieldCollection<Model>.preWatchableCreate(
    action: suspend (Model) -> Model
): WatchableFieldCollection<Model> = PreCreateSignalWatchableFieldCollection(this, action)

fun <Model : Any> WatchableFieldCollection<Model>.preWatchableDelete(
    action: suspend (Model) -> Unit
): WatchableFieldCollection<Model> = PreDeleteSignalWatchableFieldCollection(this, action)

fun <Model : HasId> WatchableFieldCollection<Model>.postWatchableDelete(
    action: suspend (Model) -> Unit
): WatchableFieldCollection<Model> = PostDeleteSignalWatchableFieldCollection(this, action)

fun <Model : HasId> WatchableFieldCollection<Model>.postWatchableChange(
    action: suspend (Model, Model) -> Unit
): WatchableFieldCollection<Model> = PostChangeSignalWatchableFieldCollection(this, action)
