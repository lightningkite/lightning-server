package com.lightningkite.ktordb

inline fun <Model : Any> FieldCollection<Model>.interceptCreate(crossinline interceptor: (Model) -> Model): FieldCollection<Model> =
    object : FieldCollection<Model> by this {
        override suspend fun insertOne(model: Model): Model = this@interceptCreate.insertOne(interceptor(model))
        override suspend fun insertMany(models: List<Model>): List<Model> =
            this@interceptCreate.insertMany(models.map(interceptor))

        override suspend fun upsertOne(condition: Condition<Model>, model: Model): Model? =
            this@interceptCreate.upsertOne(condition, interceptor(model))
    }

inline fun <Model : Any> FieldCollection<Model>.interceptReplace(crossinline interceptor: (Model) -> Model): FieldCollection<Model> =
    object : FieldCollection<Model> by this {
        override suspend fun replaceOne(condition: Condition<Model>, model: Model): Model? =
            this@interceptReplace.replaceOne(condition, interceptor(model))

        override suspend fun upsertOne(condition: Condition<Model>, model: Model): Model? =
            this@interceptReplace.upsertOne(condition, interceptor(model))
    }

inline fun <Model : Any> FieldCollection<Model>.interceptModification(crossinline interceptor: (Modification<Model>) -> Modification<Model>): FieldCollection<Model> =
    object : FieldCollection<Model> by this {
        override suspend fun findOneAndUpdate(
            condition: Condition<Model>,
            modification: Modification<Model>
        ): EntryChange<Model> = this@interceptModification.findOneAndUpdate(condition, interceptor(modification))

        override suspend fun updateMany(condition: Condition<Model>, modification: Modification<Model>): Int =
            this@interceptModification.updateMany(condition, interceptor(modification))

        override suspend fun updateOne(condition: Condition<Model>, modification: Modification<Model>): Boolean =
            this@interceptModification.updateOne(condition, interceptor(modification))
    }
