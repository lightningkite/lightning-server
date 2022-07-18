package com.lightningkite.lightningdb

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

fun <Model : Any> FieldCollection<Model>.postCreate(
    onCreate: suspend (Model)->Unit
): FieldCollection<Model> = object : FieldCollection<Model> by this@postCreate {
    override val wraps = this@postCreate
    override suspend fun insert(models: List<Model>): List<Model> {
        val result = wraps.insertMany(models)
        result.forEach { onCreate(it) }
        return result
    }

    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model> = wraps.upsertOne(condition, modification, model).also {
        if(it.old == null) onCreate(it.new!!)
    }
}
fun <Model : Any> FieldCollection<Model>.preCreate(
    onCreate: suspend (Model)->Model
): FieldCollection<Model> = interceptCreate(onCreate)

fun <Model : Any> FieldCollection<Model>.preDelete(
    onDelete: suspend (Model)->Unit
): FieldCollection<Model> = object: FieldCollection<Model> by this@preDelete {
    override val wraps = this@preDelete
    override suspend fun deleteOne(condition: Condition<Model>): Model? {
        wraps.find(condition).collect(FlowCollector(onDelete))
        return wraps.deleteOne(condition)
    }
    override suspend fun deleteMany(condition: Condition<Model>): List<Model> {
        wraps.find(condition, limit = 1).collect(FlowCollector(onDelete))
        return wraps.deleteMany(condition)
    }
    override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int {
        wraps.find(condition).collect(FlowCollector(onDelete))
        return wraps.deleteManyIgnoringOld(condition)
    }
    override suspend fun deleteOneIgnoringOld(condition: Condition<Model>): Boolean {
        wraps.find(condition, limit = 1).collect(FlowCollector(onDelete))
        return wraps.deleteOneIgnoringOld(condition)
    }
}

fun <Model : HasId<ID>, ID: Comparable<ID>> FieldCollection<Model>.postDelete(
    onDelete: suspend (Model)->Unit
): FieldCollection<Model> = object: FieldCollection<Model> by this@postDelete {
    override val wraps = this@postDelete
    override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int {
        return wraps.deleteMany(condition).also { it.forEach { onDelete(it) } }.size
    }
    override suspend fun deleteOneIgnoringOld(condition: Condition<Model>): Boolean {
        return wraps.deleteOne(condition)?.also { onDelete(it) } != null
    }
    override suspend fun deleteMany(condition: Condition<Model>): List<Model> {
        return wraps.deleteMany(condition).also { it.forEach { onDelete(it) } }
    }
    override suspend fun deleteOne(condition: Condition<Model>): Model? {
        return wraps.deleteOne(condition)?.also { onDelete(it) }
    }
}
fun <Model : HasId<ID>, ID: Comparable<ID>> FieldCollection<Model>.postChange(
    changed: suspend (Model, Model)->Unit
): FieldCollection<Model> = object: FieldCollection<Model> by this@postChange {
    override val wraps = this@postChange

    override suspend fun replaceOne(condition: Condition<Model>, model: Model): EntryChange<Model> =
        wraps.replaceOne(condition, model).also { if(it.old != null && it.new != null) changed(it.old!!, it.new!!) }

    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model>  =
        wraps.upsertOne(condition, modification, model).also { if(it.old != null && it.new != null) changed(it.old!!, it.new!!) }

    override suspend fun updateOne(condition: Condition<Model>, modification: Modification<Model>): EntryChange<Model>  =
        wraps.updateOne(condition, modification).also { if(it.old != null && it.new != null) changed(it.old!!, it.new!!) }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): CollectionChanges<Model> = wraps.updateMany(condition, modification).also { changes ->
        changes.changes.forEach {
            if(it.old != null && it.new != null)
                changed(it.old!!, it.new!!)
        }
    }

    override suspend fun replaceOneIgnoringResult(condition: Condition<Model>, model: Model): Boolean = replaceOne(condition, model).new != null

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): Boolean = upsertOne(condition, modification, model).old != null

    override suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): Boolean = updateOne(condition, modification).new != null

    override suspend fun updateManyIgnoringResult(condition: Condition<Model>, modification: Modification<Model>): Int = updateMany(condition, modification).changes.size
}
