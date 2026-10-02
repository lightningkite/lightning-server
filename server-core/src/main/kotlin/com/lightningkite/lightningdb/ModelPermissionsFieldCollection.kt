package com.lightningkite.lightningdb

import com.lightningkite.serialization.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer

/**
 * Uses [ModelPermissions] to secure a [FieldCollection].
 *
 * Writes and deletes only reach rows the user can read, and [aggregate] and [groupAggregate] over a masked property
 * (or grouped by one) skip rows the mask applies to, so they return a total over fewer rows with no sign that rows
 * were left out.
 */
open class ModelPermissionsFieldCollection<Model : Any>(
    override val wraps: FieldCollection<Model>,
    val permissions: ModelPermissions<Model>
) : FieldCollection<Model> {
    override val serializer: KSerializer<Model> get() = wraps.serializer
    private val textIndexPaths = serializer.descriptor.annotations.filterIsInstance<TextIndex>()
        .firstOrNull()?.fields?.map { DataClassPathSerializer(serializer).fromString(it).properties }
        ?: listOf()

    override suspend fun find(
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
    ): Flow<Model> {
        val sortImposedConditions = permissions.readMask.permitSort(orderBy)
        return wraps.find(
            condition = condition and permissions.read and sortImposedConditions and permissions.readMask(condition, textIndexPaths),
            orderBy = orderBy,
            skip = skip,
            limit = limit,
            maxQueryMs = maxQueryMs
        ).map { permissions.mask(it) }
    }

    override suspend fun findPartial(
        fields: Set<DataClassPathPartial<Model>>,
        condition: Condition<Model>,
        orderBy: List<SortPart<Model>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long
    ): Flow<Partial<Model>> {
        val sortImposedConditions = permissions.readMask.permitSort(orderBy)
        val allFields = fields.toMutableSet()
        permissions.readMask.pairs.forEach {
            it.first.emitReadPaths {
                allFields.add(it)
            }
        }
        return wraps.findPartial(
            fields = allFields,
            condition = condition and permissions.read and sortImposedConditions and permissions.readMask(condition, textIndexPaths),
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

    override suspend fun count(condition: Condition<Model>): Int = wraps.count(condition and permissions.read and permissions.readMask(condition, textIndexPaths))

    override suspend fun <Key> groupCount(
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>
    ): Map<Key, Int> {
        return wraps.groupCount(
            condition and permissions.read and permissions.readMask(groupBy) and permissions.readMask(condition, textIndexPaths),
            groupBy
        )
    }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        property: DataClassPath<Model, N>
    ): Double? =
        wraps.aggregate(aggregate, fullCondition(condition) and permissions.readMask(property), property)

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<Model>,
        groupBy: DataClassPath<Model, Key>,
        property: DataClassPath<Model, N>
    ): Map<Key, Double?> = wraps.groupAggregate(
        aggregate,
        fullCondition(condition) and permissions.readMask(groupBy) and permissions.readMask(property),
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
            writeCondition(condition, Modification.Assign(model)) and sortImposedConditions,
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
        return wraps.upsertOne(writeCondition(condition, modification), modification, model)
            .map { permissions.mask(it) }
    }

    override suspend fun updateOne(
        condition: Condition<Model>,
        modification: Modification<Model>,
        orderBy: List<SortPart<Model>>
    ): EntryChange<Model> {
        val sortImposedConditions = permissions.readMask.permitSort(orderBy)
        return wraps.updateOne(
            writeCondition(condition, modification) and sortImposedConditions,
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
            writeCondition(condition, modification) and sortImposedConditions,
            modification,
            orderBy
        )
    }

    override suspend fun updateManyIgnoringResult(condition: Condition<Model>, modification: Modification<Model>): Int {
        return wraps.updateManyIgnoringResult(writeCondition(condition, modification), modification)
    }

    override suspend fun deleteOneIgnoringOld(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Boolean {
        val sortImposedConditions = permissions.readMask.permitSort(orderBy)
        return wraps.deleteOneIgnoringOld(fullCondition(condition) and permissions.delete and sortImposedConditions, orderBy)
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<Model>): Int {
        return wraps.deleteManyIgnoringOld(fullCondition(condition) and permissions.delete)
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<Model>,
        model: Model,
        orderBy: List<SortPart<Model>>
    ): Boolean {
        val sortImposedConditions = permissions.readMask.permitSort(orderBy)
        return wraps.replaceOneIgnoringResult(
            writeCondition(condition, Modification.Assign(model)) and sortImposedConditions,
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
        return wraps.upsertOneIgnoringResult(writeCondition(condition, modification), modification, model)
    }

    override suspend fun updateMany(
        condition: Condition<Model>,
        modification: Modification<Model>
    ): CollectionChanges<Model> {
        return wraps.updateMany(writeCondition(condition, modification), modification)
            .map { permissions.mask(it) }
    }

    override suspend fun deleteOne(condition: Condition<Model>, orderBy: List<SortPart<Model>>): Model? {
        val sortImposedConditions = permissions.readMask.permitSort(orderBy)
        return wraps.deleteOne(fullCondition(condition) and permissions.delete and sortImposedConditions, orderBy)
            ?.let { permissions.mask(it) }
    }

    override suspend fun deleteMany(condition: Condition<Model>): List<Model> {
        return wraps.deleteMany(fullCondition(condition) and permissions.delete).map { permissions.mask(it) }
    }

    override suspend fun fullCondition(condition: Condition<Model>): Condition<Model> =
        permissions.read and condition and permissions.readMask(condition, textIndexPaths)

    // An upsert whose match is unreadable inserts instead.
    private suspend fun writeCondition(condition: Condition<Model>, modification: Modification<Model>): Condition<Model> =
        fullCondition(condition) and permissions.allowed(modification) and permissions.readMask(modification, textIndexPaths)

    override suspend fun mask(): Mask<Model> = permissions.readMask
}

fun <Model : Any> FieldCollection<Model>.withPermissions(permissions: ModelPermissions<Model>): FieldCollection<Model> =
    ModelPermissionsFieldCollection(this, permissions)