package com.lightningkite.lightningdb

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

inline fun <Model : Any> FieldCollection<Model>.interceptReplace(crossinline interceptor: suspend (Model) -> Model): FieldCollection<Model> =
    object : FieldCollection<Model> by this {
        override val wraps = this@interceptReplace

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


inline fun <Model : HasId<ID>, ID: Comparable<ID>> FieldCollection<Model>.interceptChangePlus(
    includeMassUpdates: Boolean = true,
    crossinline interceptor: suspend (Model, Modification<Model>) -> Modification<Model>
): FieldCollection<Model> =
    object : FieldCollection<Model> by this {
        override val wraps = this@interceptChangePlus
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
