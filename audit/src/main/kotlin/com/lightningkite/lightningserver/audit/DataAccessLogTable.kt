package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Records every query issued against an audited model, then performs it.
 *
 * Recording happens **before** the operation, and nothing here catches: a read whose query cannot be
 * recorded does not happen. See `plans/audit-logging.md` 6.2 for why that trade is taken and what it
 * costs.
 *
 * Wrapping the [Table] rather than instrumenting the endpoints is the whole point — this sees the
 * privileged internal reads that never reach a user, which the typed layer never observes.
 */
@OptIn(ExperimentalUuidApi::class)
internal class DataAccessLogTable<T : Any>(
    override val wraps: Table<T>,
    /** Throws when the model is audited but has no registry entry; see [DataAccessLogTable]. */
    private val modelId: suspend () -> Int,
    private val requestId: Uuid,
    private val executionId: Uuid,
    private val json: Json,
    private val nowMillis: () -> Long,
    private val write: suspend (DataAccessRecord) -> Unit,
) : Table<T> by wraps {

    private fun conditionText(condition: Condition<T>): String =
        json.encodeToString(Condition.serializer(wraps.serializer), condition)

    private fun sortText(orderBy: List<SortPart<T>>): String? =
        if (orderBy.isEmpty()) null
        else json.encodeToString(ListSerializer(SortPart.serializer(wraps.serializer)), orderBy)

    private fun modificationText(modification: Modification<T>): String =
        json.encodeToString(Modification.serializer(wraps.serializer), modification)

    private suspend fun record(
        operation: DataAccessOperation,
        condition: Condition<T>,
        sort: String? = null,
        modification: String? = null,
        groupBy: String? = null,
    ) {
        val modelId = modelId()
        write(
            DataAccessRecord(
                _id = Uuid.generateV7NonMonotonicAt(kotlin.time.Instant.fromEpochMilliseconds(nowMillis())),
                requestId = requestId,
                executionId = executionId,
                modelId = modelId,
                operation = operation,
                condition = conditionText(condition),
                sort = sort,
                modification = modification,
                groupBy = groupBy,
            )
        )
    }

    override suspend fun find(
        condition: Condition<T>,
        orderBy: List<SortPart<T>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<T> {
        record(DataAccessOperation.Find, condition, sort = sortText(orderBy))
        return wraps.find(condition, orderBy, skip, limit, maxQueryMs)
    }

    /**
     * Overridden even though the interface default routes through [find]: a backend that implements
     * this directly would otherwise be delegated straight past every override here, and read audited
     * fields with no record of it.
     */
    override suspend fun findPartial(
        fields: Set<DataClassPathPartial<T>>,
        condition: Condition<T>,
        orderBy: List<SortPart<T>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<Partial<T>> {
        record(DataAccessOperation.Find, condition, sort = sortText(orderBy))
        return wraps.findPartial(fields, condition, orderBy, skip, limit, maxQueryMs)
    }

    override suspend fun findSimilar(
        vectorField: DataClassPath<T, Embedding>,
        params: DenseVectorSearchParams,
        condition: Condition<T>,
        maxQueryMs: Long,
    ): Flow<ScoredResult<T>> {
        record(DataAccessOperation.Find, condition, groupBy = vectorField.toString())
        return wraps.findSimilar(vectorField, params, condition, maxQueryMs)
    }

    override suspend fun findSimilarSparse(
        vectorField: DataClassPath<T, SparseEmbedding>,
        params: SparseVectorSearchParams,
        condition: Condition<T>,
        maxQueryMs: Long,
    ): Flow<ScoredResult<T>> {
        record(DataAccessOperation.Find, condition, groupBy = vectorField.toString())
        return wraps.findSimilarSparse(vectorField, params, condition, maxQueryMs)
    }

    override suspend fun upsertOne(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T,
    ): EntryChange<T> {
        record(DataAccessOperation.Update, condition, modification = modificationText(modification))
        return wraps.upsertOne(condition, modification, model)
    }

    override suspend fun upsertOneIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T,
    ): Boolean {
        record(DataAccessOperation.Update, condition, modification = modificationText(modification))
        return wraps.upsertOneIgnoringResult(condition, modification, model)
    }

    override suspend fun count(condition: Condition<T>): Int {
        record(DataAccessOperation.Count, condition)
        return wraps.count(condition)
    }

    override suspend fun <Key> groupCount(
        condition: Condition<T>,
        groupBy: DataClassPath<T, Key>,
    ): Map<Key, Int> {
        record(DataAccessOperation.GroupCount, condition, groupBy = groupBy.toString())
        return wraps.groupCount(condition, groupBy)
    }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        property: DataClassPath<T, N>,
    ): Double? {
        record(DataAccessOperation.Aggregate, condition, groupBy = property.toString())
        return wraps.aggregate(aggregate, condition, property)
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        groupBy: DataClassPath<T, Key>,
        property: DataClassPath<T, N>,
    ): Map<Key, Double?> {
        record(DataAccessOperation.GroupAggregate, condition, groupBy = groupBy.toString())
        return wraps.groupAggregate(aggregate, condition, groupBy, property)
    }

    override suspend fun insert(models: Iterable<T>): List<T> {
        val list = models.toList()
        record(DataAccessOperation.Insert, Condition.Never, modification = "${list.size} inserted")
        return wraps.insert(list)
    }

    override suspend fun replaceOne(
        condition: Condition<T>,
        model: T,
        orderBy: List<SortPart<T>>,
    ): EntryChange<T> {
        record(DataAccessOperation.Replace, condition, sort = sortText(orderBy))
        return wraps.replaceOne(condition, model, orderBy)
    }

    override suspend fun replaceOneIgnoringResult(
        condition: Condition<T>,
        model: T,
        orderBy: List<SortPart<T>>,
    ): Boolean {
        record(DataAccessOperation.Replace, condition, sort = sortText(orderBy))
        return wraps.replaceOneIgnoringResult(condition, model, orderBy)
    }

    override suspend fun updateOne(
        condition: Condition<T>,
        modification: Modification<T>,
        orderBy: List<SortPart<T>>,
    ): EntryChange<T> {
        record(DataAccessOperation.Update, condition, sortText(orderBy), modificationText(modification))
        return wraps.updateOne(condition, modification, orderBy)
    }

    override suspend fun updateOneIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
        orderBy: List<SortPart<T>>,
    ): Boolean {
        record(DataAccessOperation.Update, condition, sortText(orderBy), modificationText(modification))
        return wraps.updateOneIgnoringResult(condition, modification, orderBy)
    }

    override suspend fun updateMany(
        condition: Condition<T>,
        modification: Modification<T>,
    ): CollectionChanges<T> {
        record(DataAccessOperation.Update, condition, modification = modificationText(modification))
        return wraps.updateMany(condition, modification)
    }

    override suspend fun updateManyIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
    ): Int {
        record(DataAccessOperation.Update, condition, modification = modificationText(modification))
        return wraps.updateManyIgnoringResult(condition, modification)
    }

    override suspend fun deleteOne(condition: Condition<T>, orderBy: List<SortPart<T>>): T? {
        record(DataAccessOperation.Delete, condition, sort = sortText(orderBy))
        return wraps.deleteOne(condition, orderBy)
    }

    override suspend fun deleteOneIgnoringOld(condition: Condition<T>, orderBy: List<SortPart<T>>): Boolean {
        record(DataAccessOperation.Delete, condition, sort = sortText(orderBy))
        return wraps.deleteOneIgnoringOld(condition, orderBy)
    }

    override suspend fun deleteMany(condition: Condition<T>): List<T> {
        record(DataAccessOperation.Delete, condition)
        return wraps.deleteMany(condition)
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<T>): Int {
        record(DataAccessOperation.Delete, condition)
        return wraps.deleteManyIgnoringOld(condition)
    }
}
