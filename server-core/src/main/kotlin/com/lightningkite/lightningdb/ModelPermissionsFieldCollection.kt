package com.lightningkite.lightningdb

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.reflect.KProperty1

/**
 * Uses [ModelPermissions] to secure a [FieldCollection].
 */
open class ModelPermissionsFieldCollection<Model : Any>(
    override val wraps: FieldCollection<Model>,
    val permissions: ModelPermissions<Model>
) : FieldCollection<Model> {
    override fun registerRawSignal(callback: suspend (CollectionChanges<Model>) -> Unit) {
        wraps.registerRawSignal(callback)
    }

    override suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
    ): Flow<Model> {
        val sortImposedConditions = permissions.readMask.permitSort(orderBy)
        return wraps.find(
            condition = condition and permissions.read and sortImposedConditions and permissions.readMask(condition),
            orderBy = orderBy,
            skip = skip,
            limit = limit,
            maxQueryMs = maxQueryMs
        ).map { permissions.mask(it) }
    }

    override suspend fun insert(models: Iterable<Model>): List<Model> {
        val passingModels = models.filter { permissions.create(it) }
        return wraps.insertMany(passingModels).map { permissions.mask(it) }
    }

    override suspend fun count(condition: Condition<Model>): Int = wraps.count(condition and permissions.read)

    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: KProperty1<Model, Key>
    ): Map<Key, Int> {
        return wraps.groupCount(
            condition and permissions.read and permissions.readMask(groupBy) and permissions.readMask(condition),
            groupBy
        )
    }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: KProperty1<Model, N>
    ): Double? =
        wraps.aggregate(aggregate, condition and permissions.read and permissions.readMask(condition), property)

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        groupBy: KProperty1<Model, Key>,
        property: KProperty1<Model, N>
    ): Map<Key, Double?> = wraps.groupAggregate(
        aggregate,
        condition and permissions.read and permissions.readMask(groupBy) and permissions.readMask(condition),
        groupBy,
        property
    )

    override suspend fun replaceOne(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> {
        val sortImposedConditions = permissions.readMask.permitSort(orderBy)
        return wraps.replaceOne(
            condition and permissions.allowed(Modification.Assign(model)) and sortImposedConditions,
            model,
            orderBy
        ).map { permissions.mask(it) }
    }

    override suspend fun upsertOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): EntryChange<Model> {
        if (!permissions.create(model)) throw SecurityException("You do not have permission to insert this instance.  You can only insert instances that adhere to the following condition: ${permissions.create}")
        return wraps.upsertOne(condition and permissions.allowed(modification), modification, model)
            .map { permissions.mask(it) }
    }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> {
        val sortImposedConditions = permissions.readMask.permitSort(orderBy)
        return wraps.updateOne(
            condition and permissions.allowed(modification) and sortImposedConditions,
            modification,
            orderBy
        )
            .map { permissions.mask(it) }
    }

    override suspend fun updateOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): Boolean {
        val sortImposedConditions = permissions.readMask.permitSort(orderBy)
        return wraps.updateOneIgnoringResult(
            condition and permissions.allowed(modification) and sortImposedConditions,
            modification,
            orderBy
        )
    }

    override suspend fun updateManyIgnoringResult(condition: Condition<Model>, modification: Modification<Model>): Int {
        return wraps.updateManyIgnoringResult(condition and permissions.allowed(modification), modification)
    }

    override suspend fun deleteOneIgnoringOld(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Boolean {
        val sortImposedConditions = permissions.readMask.permitSort(orderBy)
        return wraps.deleteOneIgnoringOld(condition and permissions.delete and sortImposedConditions, orderBy)
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int {
        return wraps.deleteManyIgnoringOld(condition and permissions.delete)
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): Boolean {
        val sortImposedConditions = permissions.readMask.permitSort(orderBy)
        return wraps.replaceOneIgnoringResult(
            condition and permissions.allowed(Modification.Assign(model)) and sortImposedConditions,
            model,
            orderBy
        )
    }

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<Model>,
        modification: Modification<Model>,
        model: Model
    ): Boolean {
        if (!permissions.create(model)) throw SecurityException("You do not have permission to insert this instance.  You can only insert instances that adhere to the following condition: ${permissions.create}")
        return wraps.upsertOneIgnoringResult(condition and permissions.allowed(modification), modification, model)
    }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): CollectionChanges<Model> {
        return wraps.updateMany(condition and permissions.allowed(modification), modification)
            .map { permissions.mask(it) }
    }

    override suspend fun deleteOne(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? {
        val sortImposedConditions = permissions.readMask.permitSort(orderBy)
        return wraps.deleteOne(condition and permissions.delete and sortImposedConditions, orderBy)
            ?.let { permissions.mask(it) }
    }

    override suspend fun deleteMany(condition: Condition<Model>): List<Model> {
        return wraps.deleteMany(condition and permissions.delete).map { permissions.mask(it) }
    }

    override suspend fun fullCondition(condition: Condition<Model>): Condition<Model> =
        permissions.read and condition and permissions.readMask(condition)

    override suspend fun mask(): Mask<Model> = permissions.readMask
}

fun <Model : Any> FieldCollection<Model>.withPermissions(permissions: ModelPermissions<Model>): FieldCollection<Model> =
    ModelPermissionsFieldCollection(this, permissions)