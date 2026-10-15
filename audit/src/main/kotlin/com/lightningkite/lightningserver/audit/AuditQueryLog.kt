package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.generateFromServerClock
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer

/**
 * Returns this table wrapped so that every query against it is recorded in [log] before it runs.
 *
 * Every read and write is recorded, including the privileged ones a server makes internally. A query whose
 * record cannot be written does not run.
 *
 * The model must be registered in the [AuditRegistry] passed to [log]. The registry registers the
 * [AuditedModel]s used by the server's endpoints when pre-deploy tasks run, so a model that no endpoint uses
 * has no entry, and every query against it throws [IllegalStateException] without running.
 */
context(runtime: ServerRuntime)
public fun <T : AuditedModel<ID>, ID : AuditedModel.Id<*, ID>> Table<T>.logQueries(log: AuditQueryLog): Table<T> =
    AuditQueryLogTable(this, log, runtime)

context(builder: ServerBuilder)
public fun AuditQueryLog(
    origins: OriginRecording<*>,
    registry: AuditRegistry,
    database: Runtime<Database>,
): AuditQueryLog = AuditQueryLog(origins, registry, database.registerTable("AuditDataAccessRecord"))

/** Records queries against [AuditedModel]s as [AuditQueryRecord]s. Attach it to a table with [logQueries]. */
public class AuditQueryLog(
    public val origins: OriginRecording<*>,
    public val registry: AuditRegistry,
    public val table: Runtime<Table<AuditQueryRecord>>,
)

private class AuditQueryLogTable<T : Any>(
    override val wraps: Table<T>,
    private val log: AuditQueryLog,
    private val runtime: ServerRuntime,
) : Table<T> by wraps {

    private suspend fun record(
        operation: AuditQueryRecord.Operation,
        condition: Condition<T>,
        sort: List<SortPart<T>> = emptyList(),
        modification: Modification<T>? = null,
        groupBy: String? = null,
        skip: Int? = null,
        limit: Int? = null,
        fields: String? = null,
        aggregate: String? = null,
    ): Unit = with(runtime) {
        val json = internalSerialization.json
        val modelType = log.registry.assignments.await().modelType(wraps.serializer.descriptor)
        log.origins.withCurrentOrigin { origin ->
            log.table().insertOne(
                AuditQueryRecord(
                    _id = AuditQueryRecord.ID(UuidV7.generateFromServerClock()),
                    origin = origin,
                    operation = operation,
                    modelType = modelType,
                    condition = json.encodeToString(Condition.serializer(wraps.serializer), condition),
                    sort = sort.takeIf { it.isNotEmpty() }
                        ?.let { json.encodeToString(ListSerializer(SortPart.serializer(wraps.serializer)), it) },
                    modification = modification?.let { json.encodeToString(Modification.serializer(wraps.serializer), it) },
                    groupBy = groupBy,
                    skip = skip,
                    limit = limit,
                    fields = fields,
                    aggregate = aggregate,
                )
            )
        }
    }

    override suspend fun find(
        condition: Condition<T>,
        orderBy: List<SortPart<T>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<T> {
        record(AuditQueryRecord.Operation.Find, condition, sort = orderBy, skip = skip, limit = limit)
        return wraps.find(condition, orderBy, skip, limit, maxQueryMs)
    }

    // Claude: overridden even though the default goes through find, because a backend may implement it directly.
    override suspend fun findPartial(
        fields: Set<DataClassPathPartial<T>>,
        condition: Condition<T>,
        orderBy: List<SortPart<T>>,
        skip: Int,
        limit: Int,
        maxQueryMs: Long,
    ): Flow<Partial<T>> {
        record(
            AuditQueryRecord.Operation.Find, condition, sort = orderBy, skip = skip, limit = limit,
            fields = fields.joinToString(",") { it.toString() },
        )
        return wraps.findPartial(fields, condition, orderBy, skip, limit, maxQueryMs)
    }

    override suspend fun findSimilar(
        vectorField: DataClassPath<T, Embedding>,
        params: DenseVectorSearchParams,
        condition: Condition<T>,
        maxQueryMs: Long,
    ): Flow<ScoredResult<T>> {
        record(AuditQueryRecord.Operation.Find, condition, groupBy = vectorField.toString(), aggregate = params.toString())
        return wraps.findSimilar(vectorField, params, condition, maxQueryMs)
    }

    override suspend fun findSimilarSparse(
        vectorField: DataClassPath<T, SparseEmbedding>,
        params: SparseVectorSearchParams,
        condition: Condition<T>,
        maxQueryMs: Long,
    ): Flow<ScoredResult<T>> {
        record(AuditQueryRecord.Operation.Find, condition, groupBy = vectorField.toString(), aggregate = params.toString())
        return wraps.findSimilarSparse(vectorField, params, condition, maxQueryMs)
    }

    override suspend fun count(condition: Condition<T>): Int {
        record(AuditQueryRecord.Operation.Count, condition)
        return wraps.count(condition)
    }

    override suspend fun <Key> groupCount(condition: Condition<T>, groupBy: DataClassPath<T, Key>): Map<Key, Int> {
        record(AuditQueryRecord.Operation.GroupCount, condition, groupBy = groupBy.toString())
        return wraps.groupCount(condition, groupBy)
    }

    override suspend fun <N : Number?> aggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        property: DataClassPath<T, N>,
    ): Double? {
        record(AuditQueryRecord.Operation.Aggregate, condition, groupBy = property.toString(), aggregate = aggregate.toString())
        return wraps.aggregate(aggregate, condition, property)
    }

    override suspend fun <N : Number?, Key> groupAggregate(
        aggregate: Aggregate,
        condition: Condition<T>,
        groupBy: DataClassPath<T, Key>,
        property: DataClassPath<T, N>,
    ): Map<Key, Double?> {
        record(
            AuditQueryRecord.Operation.GroupAggregate, condition, groupBy = groupBy.toString(),
            aggregate = "$aggregate over $property",
        )
        return wraps.groupAggregate(aggregate, condition, groupBy, property)
    }

    override suspend fun insert(models: Iterable<T>): List<T> {
        val list = models.toList()
        // Claude: an insert matches nothing, so the condition is Never and the count goes in limit.
        record(AuditQueryRecord.Operation.Insert, Condition.Never, limit = list.size)
        return wraps.insert(list)
    }

    override suspend fun replaceOne(condition: Condition<T>, model: T, orderBy: List<SortPart<T>>): EntryChange<T> {
        record(AuditQueryRecord.Operation.Replace, condition, sort = orderBy)
        return wraps.replaceOne(condition, model, orderBy)
    }

    override suspend fun replaceOneIgnoringResult(condition: Condition<T>, model: T, orderBy: List<SortPart<T>>): Boolean {
        record(AuditQueryRecord.Operation.Replace, condition, sort = orderBy)
        return wraps.replaceOneIgnoringResult(condition, model, orderBy)
    }

    override suspend fun upsertOne(condition: Condition<T>, modification: Modification<T>, model: T): EntryChange<T> {
        record(AuditQueryRecord.Operation.Update, condition, modification = modification)
        return wraps.upsertOne(condition, modification, model)
    }

    override suspend fun upsertOneIgnoringResult(condition: Condition<T>, modification: Modification<T>, model: T): Boolean {
        record(AuditQueryRecord.Operation.Update, condition, modification = modification)
        return wraps.upsertOneIgnoringResult(condition, modification, model)
    }

    override suspend fun updateOne(condition: Condition<T>, modification: Modification<T>, orderBy: List<SortPart<T>>): EntryChange<T> {
        record(AuditQueryRecord.Operation.Update, condition, sort = orderBy, modification = modification)
        return wraps.updateOne(condition, modification, orderBy)
    }

    override suspend fun updateOneIgnoringResult(condition: Condition<T>, modification: Modification<T>, orderBy: List<SortPart<T>>): Boolean {
        record(AuditQueryRecord.Operation.Update, condition, sort = orderBy, modification = modification)
        return wraps.updateOneIgnoringResult(condition, modification, orderBy)
    }

    override suspend fun updateMany(condition: Condition<T>, modification: Modification<T>): CollectionChanges<T> {
        record(AuditQueryRecord.Operation.Update, condition, modification = modification)
        return wraps.updateMany(condition, modification)
    }

    override suspend fun updateManyIgnoringResult(condition: Condition<T>, modification: Modification<T>): Int {
        record(AuditQueryRecord.Operation.Update, condition, modification = modification)
        return wraps.updateManyIgnoringResult(condition, modification)
    }

    override suspend fun deleteOne(condition: Condition<T>, orderBy: List<SortPart<T>>): T? {
        record(AuditQueryRecord.Operation.Delete, condition, sort = orderBy)
        return wraps.deleteOne(condition, orderBy)
    }

    override suspend fun deleteOneIgnoringOld(condition: Condition<T>, orderBy: List<SortPart<T>>): Boolean {
        record(AuditQueryRecord.Operation.Delete, condition, sort = orderBy)
        return wraps.deleteOneIgnoringOld(condition, orderBy)
    }

    override suspend fun deleteMany(condition: Condition<T>): List<T> {
        record(AuditQueryRecord.Operation.Delete, condition)
        return wraps.deleteMany(condition)
    }

    override suspend fun deleteManyIgnoringOld(condition: Condition<T>): Int {
        record(AuditQueryRecord.Operation.Delete, condition)
        return wraps.deleteManyIgnoringOld(condition)
    }
}
