package com.lightningkite.ktordb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.reflect.KType

open class ModelPermissionsFieldCollection<Model : Any>(
    val base: FieldCollection<Model>,
    val permissions: ModelPermissions<Model>
) : FieldCollection<Model> {
    override suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
    ): Flow<Model> {
        val sortImposedConditions = orderBy
            .mapNotNull { permissions.readFields[it.field]?.condition }
            .let { Condition.And(it) }
        return base.find(
            condition = condition and permissions.read and sortImposedConditions,
            orderBy = orderBy,
            skip = skip,
            limit = limit,
            maxQueryMs = maxQueryMs
        ).map { permissions.mask(it) }
    }

    override suspend fun insertOne(model: Model): Model {
        if(!permissions.create(model)) throw SecurityException("You do not have permission to insert this instance.  You can only insert instances that adhere to the following condition: ${permissions.create}")
        return base.insertOne(model).let { permissions.mask(it) }
    }

    override suspend fun insertMany(models: List<Model>): List<Model> {
        val passingModels = models.filter { permissions.create(it) }
        return base.insertMany(passingModels).map { permissions.mask(it) }
    }

    override suspend fun replaceOne(condition: Condition<Model>, model: Model): Model? {
        val modification = Modification.Assign(model)
        return base.findOneAndUpdate(
            condition and permissions.allowed(modification),
            modification
        ).new?.let { permissions.mask(it) }
    }

    override suspend fun upsertOne(condition: Condition<Model>, model: Model): Model? {
        val modification = Modification.Assign(model)
        return base.upsertOne(condition and permissions.allowed(modification), model)?.let { permissions.mask(it) }
    }

    override suspend fun updateOne(condition: Condition<Model>, modification: Modification<Model>): Boolean {
        return base.updateOne(condition and permissions.allowed(modification), modification)
    }

    override suspend fun findOneAndUpdate(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): EntryChange<Model> {
        return base.findOneAndUpdate(condition and permissions.allowed(modification), modification).map { permissions.mask(it) }
    }

    override suspend fun updateMany(condition: Condition<Model>, modification: Modification<Model>): Int {
        return base.updateMany(condition and permissions.allowed(modification), modification)
    }

    override suspend fun deleteOne(condition: Condition<Model>): Boolean {
        return base.deleteOne(condition and permissions.delete)
    }

    override suspend fun deleteMany(condition: Condition<Model>): Int {
        return base.deleteMany(condition and permissions.delete)
    }

    override suspend fun watch(condition: Condition<Model>): Flow<EntryChange<Model>> = base.watch(condition and permissions.read)
        .map { it.map { permissions.mask(it) } }

    override suspend fun count(condition: Condition<Model>): Int = base.count(condition and permissions.read)

    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: DataClassProperty<Model, Key>
    ): Map<Key, Int> {
        return base.groupCount(condition and permissions.read and (permissions.readFields[groupBy]?.condition ?: Condition.Always()), groupBy)
    }

    override suspend fun <N : Number> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: DataClassProperty<Model, N>
    ): Double? = base.aggregate(aggregate, condition and permissions.read, property)

    override suspend fun <N: Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        groupBy: DataClassProperty<Model, Key>,
        property: DataClassProperty<Model, N>
    ): Map<Key, Double?> = base.groupAggregate(aggregate, condition and permissions.read and (permissions.readFields[groupBy]?.condition ?: Condition.Always()), groupBy, property)
}

fun <Model: Any> FieldCollection<Model>.withPermissions(permissions: ModelPermissions<Model>): FieldCollection<Model> = ModelPermissionsFieldCollection(this, permissions)