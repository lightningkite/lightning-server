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

        override suspend fun replaceOne(condition: Condition<Model>, model: Model): EntryChange<Model> =
            wraps.replaceOne(
                condition,
                interceptor(model)
            )

        override suspend fun replaceOneIgnoringResult(condition: Condition<Model>, model: Model): Boolean =
            wraps.replaceOneIgnoringResult(
                condition,
                interceptor(model)
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
            modification: Modification<Model>
        ): EntryChange<Model> = wraps.updateOne(condition, interceptor(modification))

        override suspend fun updateOneIgnoringResult(
            condition: Condition<Model>,
            modification: Modification<Model>
        ): Boolean = wraps.updateOneIgnoringResult(condition, interceptor(modification))

        override suspend fun updateMany(
            condition: Condition<Model>,
            modification: Modification<Model>
        ): CollectionChanges<Model> = wraps.updateMany(condition, interceptor(modification))

        override suspend fun updateManyIgnoringResult(
            condition: Condition<Model>,
            modification: Modification<Model>
        ): Int = wraps.updateManyIgnoringResult(condition, interceptor(modification))
    }
