package com.lightningkite.lightningdb

import kotlinx.coroutines.flow.FlowCollector

/**
 * Runs after an item is created.
 */
fun <Model : Any> FieldCollection<Model>.postCreate(
    onCreate: suspend (Model) -> Unit
): FieldCollection<Model> = object : FieldCollection<Model> by this@postCreate {
    override val wraps = this@postCreate
    override suspend fun insert(models: Iterable<Model>): List<Model> {
        val result = wraps.insertMany(models)
        result.forEach { onCreate(it) }
        return result
    }

    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model> = wraps.upsertOne(condition, modification, model).also {
        if (it.old == null) onCreate(it.new!!)
    }
}

@Deprecated("Use 'interceptDelete' instead", ReplaceWith("interceptDelete(onDelete)"))
fun <Model : Any> FieldCollection<Model>.preDelete(onDelete: suspend (Model) -> Unit): FieldCollection<Model> = interceptDelete(onDelete)

/**
 * Runs after an item is deleted.
 */
fun <Model : HasId<ID>, ID : Comparable<ID>> FieldCollection<Model>.postDelete(
    onDelete: suspend (Model) -> Unit
): FieldCollection<Model> = object : FieldCollection<Model> by this@postDelete {
    override val wraps = this@postDelete
    override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int {
        return wraps.deleteMany(condition).also { it.forEach { onDelete(it) } }.size
    }

    override suspend fun deleteOneIgnoringOld(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Boolean {
        return wraps.deleteOne(condition, orderBy)?.also { onDelete(it) } != null
    }

    override suspend fun deleteMany(condition: Condition<Model>): List<Model> {
        return wraps.deleteMany(condition).also { it.forEach { onDelete(it) } }
    }

    override suspend fun deleteOne(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? {
        return wraps.deleteOne(condition, orderBy)?.also { onDelete(it) }
    }
}

/**
 * Runs after an existing item is changed.
 */
fun <Model : HasId<ID>, ID : Comparable<ID>> FieldCollection<Model>.postChange(
    changed: suspend (Model, Model) -> Unit
): FieldCollection<Model> = object : FieldCollection<Model> by this@postChange {
    override val wraps = this@postChange

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> =
        wraps.replaceOne(condition, model, orderBy)
            .also { if (it.old != null && it.new != null) changed(it.old!!, it.new!!) }

    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model> =
        wraps.upsertOne(condition, modification, model)
            .also { if (it.old != null && it.new != null) changed(it.old!!, it.new!!) }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> =
        wraps.updateOne(condition, modification, orderBy)
            .also { if (it.old != null && it.new != null) changed(it.old!!, it.new!!) }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): CollectionChanges<Model> = wraps.updateMany(condition, modification).also { changes ->
        changes.changes.forEach {
            if (it.old != null && it.new != null)
                changed(it.old!!, it.new!!)
        }
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): Boolean = replaceOne(
        condition,
        model
    ).new != null

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): Boolean = upsertOne(condition, modification, model).old != null

    override suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean = updateOne(condition, modification).new != null

    override suspend fun updateManyIgnoringResult(condition: Condition<Model>, modification: Modification<Model>): Int =
        updateMany(condition, modification).changes.size
}

/**
 * Runs after any value is added or modified in the database.
 */
fun <Model : HasId<ID>, ID : Comparable<ID>> FieldCollection<Model>.postNewValue(
    changed: suspend (Model) -> Unit
): FieldCollection<Model> = object : FieldCollection<Model> by this@postNewValue {
    override val wraps = this@postNewValue

    override suspend fun insert(models: Iterable<Model>): List<Model> {
        return wraps.insert(models).onEach { changed(it) }
    }

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> =
        wraps.replaceOne(condition, model, orderBy).also { if (it.old != null && it.new != null) changed(it.new!!) }

    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model> =
        wraps.upsertOne(condition, modification, model).also { it.new?.let { changed(it) } }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> =
        wraps.updateOne(condition, modification, orderBy)
            .also { if (it.old != null && it.new != null) changed(it.new!!) }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): CollectionChanges<Model> = wraps.updateMany(condition, modification).also { changes ->
        changes.changes.forEach {
            if (it.old != null && it.new != null)
                changed(it.new!!)
        }
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): Boolean = replaceOne(
        condition,
        model
    ).new != null

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): Boolean = upsertOne(condition, modification, model).old != null

    override suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean = updateOne(condition, modification).new != null

    override suspend fun updateManyIgnoringResult(condition: Condition<Model>, modification: Modification<Model>): Int =
        updateMany(condition, modification).changes.size
}


/**
 * Intercept all kinds of creates, including [FieldCollection.insert], [FieldCollection.upsertOne], and [FieldCollection.upsertOneIgnoringResult].
 * Allows you to modify the object before it is actually created.
 */
inline fun <Model : Any> FieldCollection<Model>.interceptCreate(crossinline interceptor: suspend (Model) -> Model): FieldCollection<Model> =
    object : FieldCollection<Model> by this {
        override val wraps = this@interceptCreate
        override suspend fun insert(models: Iterable<Model>): List<Model> =
            wraps.insertMany(models.map { interceptor(it) })

        override suspend fun upsertOne(
            condition: Condition<Model>,
            modification: Modification<Model>,
            model: Model
        ): EntryChange<Model> = wraps.upsertOne(condition, modification, interceptor(model))

        override suspend fun upsertOneIgnoringResult(
            condition: Condition<Model>,
            modification: Modification<Model>,
            model: Model
        ): Boolean = wraps.upsertOneIgnoringResult(condition, modification, interceptor(model))
    }

/**
 * Intercepts all kinds of replace operations.
 */
inline fun <Model : Any> FieldCollection<Model>.interceptReplace(crossinline interceptor: suspend (Model) -> Model): FieldCollection<Model> =
    object : FieldCollection<Model> by this {
        override val wraps = this@interceptReplace

        override suspend fun upsertOne(
            condition: Condition<Model>,
            modification: Modification<Model>,
            model: Model
        ): EntryChange<Model> = if(modification is Modification.Assign)
            wraps.upsertOne(condition, Modification.Assign(interceptor(modification.value)), model)
        else
            wraps.upsertOne(condition, modification, model)

        override suspend fun upsertOneIgnoringResult(
            condition: Condition<Model>,
            modification: Modification<Model>,
            model: Model
        ): Boolean = if(modification is Modification.Assign)
            wraps.upsertOneIgnoringResult(condition, Modification.Assign(interceptor(modification.value)), model)
        else
            wraps.upsertOneIgnoringResult(condition, modification, model)

        override suspend fun replaceOne(
            condition: Condition<Model>,
            model: Model,
            orderBy: List<SortPart<Model>>
        ): EntryChange<Model> =
            wraps.replaceOne(
                condition,
                interceptor(model),
                orderBy
            )

        override suspend fun replaceOneIgnoringResult(
            condition: Condition<Model>,
            model: Model,
            orderBy: List<SortPart<Model>>
        ): Boolean =
            wraps.replaceOneIgnoringResult(
                condition,
                interceptor(model),
                orderBy
            )
    }

/**
 * Intercepts all modifications sent to the database.
 */
inline fun <Model : Any> FieldCollection<Model>.interceptModification(crossinline interceptor: suspend (Modification<Model>) -> Modification<Model>): FieldCollection<Model> =
    object : FieldCollection<Model> by this {
        override val wraps = this@interceptModification
        override suspend fun upsertOne(
            condition: Condition<Model>,
            modification: Modification<Model>,
            model: Model
        ): EntryChange<Model> = wraps.upsertOne(condition, interceptor(modification), model)

        override suspend fun upsertOneIgnoringResult(
            condition: Condition<Model>,
            modification: Modification<Model>,
            model: Model
        ): Boolean = wraps.upsertOneIgnoringResult(condition, interceptor(modification), model)

        override suspend fun updateOne(
            condition: Condition<Model>,
            modification: Modification<Model>,
            orderBy: List<SortPart<Model>>
        ): EntryChange<Model> = wraps.updateOne(condition, interceptor(modification), orderBy)

        override suspend fun updateOneIgnoringResult(
            condition: Condition<Model>,
            modification: Modification<Model>,
            orderBy: List<SortPart<Model>>
        ): Boolean = wraps.updateOneIgnoringResult(condition, interceptor(modification), orderBy)

        override suspend fun updateMany(
            condition: Condition<Model>,
            modification: Modification<Model>
        ): CollectionChanges<Model> = wraps.updateMany(condition, interceptor(modification))

        override suspend fun updateManyIgnoringResult(
            condition: Condition<Model>,
            modification: Modification<Model>
        ): Int = wraps.updateManyIgnoringResult(condition, interceptor(modification))
    }

/**
 * Intercepts all changes sent to the database, including inserting, replacing, upserting, and updating.
 */
inline fun <Model : Any> FieldCollection<Model>.interceptChange(crossinline interceptor: suspend (Modification<Model>) -> Modification<Model>): FieldCollection<Model> =
    object : FieldCollection<Model> by this {
        override val wraps = this@interceptChange
        override suspend fun insert(models: Iterable<Model>): List<Model> =
            wraps.insert(models.map { interceptor(Modification.Assign(it))(it) })

        override suspend fun replaceOne(condition: Condition<Model>, model: Model, orderBy: List<SortPart<Model>>): EntryChange<Model> =
            wraps.replaceOne(
                condition,
                interceptor(Modification.Assign(model))(model),
                orderBy
            )

        override suspend fun replaceOneIgnoringResult(
            condition: Condition<Model>,
            model: Model,
            orderBy: List<SortPart<Model>>
        ): Boolean =
            wraps.replaceOneIgnoringResult(
                condition,
                interceptor(Modification.Assign(model))(model),
                orderBy
            )

        override suspend fun upsertOne(
            condition: Condition<Model>,
            modification: Modification<Model>,
            model: Model
        ): EntryChange<Model> = wraps.upsertOne(condition, interceptor(modification), interceptor(Modification.Assign(model))(model))

        override suspend fun upsertOneIgnoringResult(
            condition: Condition<Model>,
            modification: Modification<Model>,
            model: Model
        ): Boolean = wraps.upsertOneIgnoringResult(condition, interceptor(modification), interceptor(Modification.Assign(model))(model))

        override suspend fun updateOne(
            condition: Condition<Model>,
            modification: Modification<Model>,
            orderBy: List<SortPart<Model>>
        ): EntryChange<Model> = wraps.updateOne(condition, interceptor(modification), orderBy)

        override suspend fun updateOneIgnoringResult(
            condition: Condition<Model>,
            modification: Modification<Model>,
            orderBy: List<SortPart<Model>>
        ): Boolean = wraps.updateOneIgnoringResult(condition, interceptor(modification), orderBy)

        override suspend fun updateMany(
            condition: Condition<Model>,
            modification: Modification<Model>
        ): CollectionChanges<Model> = wraps.updateMany(condition, interceptor(modification))

        override suspend fun updateManyIgnoringResult(
            condition: Condition<Model>,
            modification: Modification<Model>
        ): Int = wraps.updateManyIgnoringResult(condition, interceptor(modification))
    }

/**
 * Intercepts all changes sent to the database, including inserting, replacing, upserting, and updating.
 * Also gives you an instance of the model that will be changed.
 * This is significantly more expensive, as we must retrieve the data before we can calculate the change.
 */
inline fun <Model : HasId<ID>, ID: Comparable<ID>> FieldCollection<Model>.interceptChangePerInstance(
    includeMassUpdates: Boolean = true,
    crossinline interceptor: suspend (Model, Modification<Model>) -> Modification<Model>
): FieldCollection<Model> =
    object : FieldCollection<Model> by this {
        override val wraps = this@interceptChangePerInstance
        override suspend fun insert(models: Iterable<Model>): List<Model> =
            wraps.insert(models.map { interceptor(it, Modification.Assign(it))(it) })

        override suspend fun replaceOne(condition: Condition<Model>, model: Model, orderBy: List<SortPart<Model>>): EntryChange<Model> {
            val current = wraps.findOne(condition) ?: return EntryChange(null, null)
            return wraps.replaceOne(
                Condition.OnField(HasId<ID>::_id, Condition.Equal(current._id)),
                interceptor(model, Modification.Assign(model))(model),
                orderBy
            )
        }

        override suspend fun replaceOneIgnoringResult(
            condition: Condition<Model>,
            model: Model,
            orderBy: List<SortPart<Model>>
        ): Boolean {
            val current = wraps.findOne(condition) ?: return false
            return wraps.replaceOneIgnoringResult(
                Condition.OnField(HasId<ID>::_id, Condition.Equal(current._id)),
                interceptor(model, Modification.Assign(model))(model),
                orderBy
            )
        }
        override suspend fun upsertOne(
            condition: Condition<Model>,
            modification: Modification<Model>,
            model: Model
        ): EntryChange<Model> {
            val current = wraps.findOne(condition) ?: return EntryChange(null, null)
            val changed = interceptor(current, modification)
            return wraps.upsertOne(Condition.OnField(HasId<ID>::_id, Condition.Equal(current._id)), changed, changed(model))
        }

        override suspend fun upsertOneIgnoringResult(
            condition: Condition<Model>,
            modification: Modification<Model>,
            model: Model
        ): Boolean {
            val current = wraps.findOne(condition) ?: return false
            val changed = interceptor(current, modification)
            return wraps.upsertOneIgnoringResult(Condition.OnField(HasId<ID>::_id, Condition.Equal(current._id)), changed, changed(model))
        }

        override suspend fun updateOne(
            condition: Condition<Model>,
            modification: Modification<Model>,
            orderBy: List<SortPart<Model>>
        ): EntryChange<Model> {
            val current = wraps.findOne(condition) ?: return EntryChange(null, null)
            return wraps.updateOne(Condition.OnField(HasId<ID>::_id, Condition.Equal(current._id)), interceptor(current, modification), orderBy)
        }

        override suspend fun updateOneIgnoringResult(
            condition: Condition<Model>,
            modification: Modification<Model>,
            orderBy: List<SortPart<Model>>
        ): Boolean {
            val current = wraps.findOne(condition) ?: return false
            return wraps.updateOneIgnoringResult(Condition.OnField(HasId<ID>::_id, Condition.Equal(current._id)), interceptor(current, modification), orderBy)
        }

        override suspend fun updateMany(
            condition: Condition<Model>,
            modification: Modification<Model>
        ): CollectionChanges<Model> {
            if(!includeMassUpdates) return wraps.updateMany(condition, modification)
            val all = ArrayList<EntryChange<Model>>()
            wraps.find(condition).collect {
                val altMod = interceptor(it, modification)
                all.add(wraps.updateOne(condition, altMod))
            }
            return CollectionChanges(all)
        }

        override suspend fun updateManyIgnoringResult(
            condition: Condition<Model>,
            modification: Modification<Model>
        ): Int {
            if(!includeMassUpdates) return wraps.updateManyIgnoringResult(condition, modification)
            var count = 0
            wraps.find(condition).collect {
                val altMod = interceptor(it, modification)
                if(wraps.updateOneIgnoringResult(condition, altMod)) count++
            }
            return count
        }
    }


/**
 * Runs before an item is deleted.
 */
fun <Model : Any> FieldCollection<Model>.interceptDelete(
    onDelete: suspend (Model) -> Unit
): FieldCollection<Model> = object : FieldCollection<Model> by this@interceptDelete {
    override val wraps = this@interceptDelete
    override suspend fun deleteOne(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? {
        wraps.find(condition, limit = 1, orderBy = orderBy).collect(FlowCollector(onDelete))
        return wraps.deleteOne(condition, orderBy)
    }

    override suspend fun deleteMany(condition: Condition<Model>): List<Model> {
        wraps.find(condition).collect(FlowCollector(onDelete))
        return wraps.deleteMany(condition)
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int {
        wraps.find(condition).collect(FlowCollector(onDelete))
        return wraps.deleteManyIgnoringOld(condition)
    }

    override suspend fun deleteOneIgnoringOld(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Boolean {
        wraps.find(condition, limit = 1, orderBy = orderBy).collect(FlowCollector(onDelete))
        return wraps.deleteOneIgnoringOld(condition, orderBy)
    }
}
