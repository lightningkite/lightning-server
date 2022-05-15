package com.lightningkite.ktordb

import kotlinx.coroutines.flow.*

suspend fun <Model> Flow<Model>.collectChunked(chunkSize: Int, action: suspend (List<Model>) -> Unit) {
    val list = ArrayList<Model>()
    this.collect {
        list.add(it)
        if (list.size >= chunkSize) {
            action(list)
            list.clear()
        }
    }
    action(list)
}

class PostCreateSignalFieldCollection<Model : Any>(
    val wraps: FieldCollection<Model>,
    val onCreate: suspend (Model) -> Unit,
) : FieldCollection<Model> by wraps {
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
class PreCreateSignalFieldCollection<Model: Any>(
    val wraps: FieldCollection<Model>,
    val onCreate: suspend (Model)->Model,
): FieldCollection<Model> by wraps {
    override suspend fun insertOne(model: Model): Model {
        return wraps.insertOne(onCreate(model))
    }
    override suspend fun insertMany(models: List<Model>): List<Model> {
        return wraps.insertMany(models.map { onCreate(it) })
    }
}
class PreDeleteSignalFieldCollection<Model: Any>(
    val wraps: FieldCollection<Model>,
    val onDelete: suspend (Model)->Unit,
): FieldCollection<Model> by wraps {
    override suspend fun deleteMany(condition: Condition<Model>): Int {
        wraps.find(condition).collect(FlowCollector(onDelete))
        return wraps.deleteMany(condition)
    }
    override suspend fun deleteOne(condition: Condition<Model>): Boolean {
        wraps.find(condition, limit = 1).collect(FlowCollector(onDelete))
        return wraps.deleteOne(condition)
    }
}

class PostDeleteSignalFieldCollection<Model: HasId<ID>, ID: Comparable<ID>>(
    val wraps: FieldCollection<Model>,
    val onDelete: suspend (Model)->Unit,
): FieldCollection<Model> by wraps {
    override suspend fun deleteMany(condition: Condition<Model>): Int {
        var count = 0
        wraps.find(condition).collectChunked(1000) { list ->
            count += wraps.deleteMany(startChain<Model>()[HasIdFields._id()] inside list.map { it._id })
            list.forEach { onDelete(it) }
        }
        return count
    }
    override suspend fun deleteOne(condition: Condition<Model>): Boolean {
        val toDelete = wraps.find(condition, limit = 1).toList()
        val result = wraps.deleteOne(startChain<Model>()[HasIdFields._id()] inside toDelete.map { it._id })
        toDelete.forEach { onDelete(it) }
        return result
    }
}
class PostChangeSignalFieldCollection<Model: HasId<ID>, ID: Comparable<ID>>(
    val wraps: FieldCollection<Model>,
    val changed: suspend (before: Model, after: Model)->Unit,
): FieldCollection<Model> by wraps {
    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model
    ): Model? {
        val before = wraps.find(condition, limit = 1).firstOrNull() ?: return null
        val result = wraps.replaceOne(condition, model)
        if(result != null) changed(before, result)
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
            count += wraps.updateMany(startChain<Model>()[HasIdFields._id()] inside list.map { it._id }, modification)
            list.forEach { changed(it, modification(it)) }
        }
        return count
    }

    override suspend fun findOneAndUpdate(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): EntryChange<Model> {
        val change = wraps.findOneAndUpdate(condition, modification)
        if(change.old != null && change.new != null)
            changed(change.old!!, change.new!!)
        return change
    }
}


fun <Model : Any> FieldCollection<Model>.postCreate(
    action: suspend (Model)->Unit
): FieldCollection<Model> = PostCreateSignalFieldCollection(this, action)
fun <Model : Any> FieldCollection<Model>.preCreate(
    action: suspend (Model)->Model
): FieldCollection<Model> = PreCreateSignalFieldCollection(this, action)
fun <Model : Any> FieldCollection<Model>.preDelete(
    action: suspend (Model)->Unit
): FieldCollection<Model> = PreDeleteSignalFieldCollection(this, action)
fun <Model : HasId<ID>, ID: Comparable<ID>> FieldCollection<Model>.postDelete(
    action: suspend (Model)->Unit
): FieldCollection<Model> = PostDeleteSignalFieldCollection(this, action)
fun <Model : HasId<ID>, ID: Comparable<ID>> FieldCollection<Model>.postChange(
    action: suspend (Model, Model)->Unit
): FieldCollection<Model> = PostChangeSignalFieldCollection(this, action)
