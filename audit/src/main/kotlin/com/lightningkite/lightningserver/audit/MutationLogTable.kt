package com.lightningkite.lightningserver.audit

import com.lightningkite.services.database.*
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private val mutationLogger = KotlinLogging.logger("com.lightningkite.lightningserver.audit.MutationLog")

/**
 * Performs a mutation, then records what it actually changed.
 *
 * Only the mutating members are overridden; reads are delegated straight through, because a read is
 * the data access log's subject and recording it here would duplicate that layer in a table with the
 * wrong shape for it.
 *
 * ## After, not before
 * The disclosure and data access logs record first and throw, because the thing they guard must not
 * happen unless it was recorded. That is not available here: the effect *is* the record, so there is
 * nothing to write until the change has been made — and once it has, throwing would report failure
 * for something that happened, inviting a retry that applies it twice. So this fails open: a write
 * that cannot be recorded is logged loudly and the mutation stands. The cost, stated plainly: an
 * attacker who can make the audit database unavailable can mutate unrecorded. Pair this layer with
 * the data access log, which fails closed and records the attempt, where that matters.
 *
 * ## The `Ignoring*` upgrade
 * Under [BulkMutationDetail.RecordEveryRow] each `…IgnoringResult` / `…IgnoringOld` call is performed
 * through its effect-returning equivalent so the changed rows are visible to record. The value handed
 * back is then derived to match what the skipped method's contract promises, so no caller can tell
 * the difference. Those derivations are the delicate part of this class and are noted individually.
 */
@OptIn(ExperimentalUuidApi::class)
internal class MutationLogTable<T : Any>(
    override val wraps: Table<T>,
    /** Throws when the model is audited but has no registry entry; see [mutationLogged]. */
    private val modelId: suspend () -> Int,
    private val requestId: Uuid?,
    private val attributedTo: Uuid,
    private val executionId: Uuid,
    private val causedBy: Uuid?,
    private val rootExecutionId: Uuid,
    private val initiatorKind: String,
    private val initiator: String,
    private val json: Json,
    private val nowMillis: () -> Long,
    private val write: suspend (MutationRecord) -> Unit,
    private val bulkDetail: BulkMutationDetail,
) : Table<T> by wraps {

    /**
     * The model as JSON, kept as a tree so that the row's id and its text come from one encode.
     */
    private fun encode(model: T): JsonObject =
        json.encodeToJsonElement(wraps.serializer, model).jsonObject

    /**
     * The `_id` as text, because the models this wraps are not all keyed by `Uuid` — a `String`-keyed
     * model is a legal audited model. A non-primitive key renders as its JSON, which is still a
     * faithful identifier even if it is an awkward one to type into a query.
     */
    private fun idOf(model: JsonObject): String = model["_id"].let {
        if (it == null) throw IllegalStateException(
            "Mutation of \"" + wraps.serializer.descriptor.auditSerialName + "\" cannot be recorded: " +
                "the model has no _id, so no record could say which row changed."
        )
        if (it is JsonPrimitive) it.content else it.toString()
    }

    private fun row(
        modelId: Int,
        operation: MutationOperation,
        recordId: String?,
        old: JsonObject?,
        new: JsonObject?,
        affectedCount: Int? = null,
    ) = MutationRecord(
        _id = Uuid.generateV7NonMonotonicAt(kotlin.time.Instant.fromEpochMilliseconds(nowMillis())),
        requestId = requestId,
        attributedTo = attributedTo,
        executionId = executionId,
        causedBy = causedBy,
        rootExecutionId = rootExecutionId,
        initiatorKind = initiatorKind,
        initiator = initiator,
        modelId = modelId,
        recordId = recordId,
        operation = operation,
        old = old?.toString(),
        new = new?.toString(),
        affectedCount = affectedCount,
    )

    /**
     * Writes the rows for one call, swallowing any failure.
     *
     * The whole call's rows share one guard rather than one each: a sink that fails on the third row
     * of ten will fail on the rest, and ten copies of the same error in the log obscures rather than
     * informs.
     */
    private suspend fun audit(block: suspend (modelId: Int) -> Unit) {
        try {
            block(modelId())
        } catch (e: Exception) {
            // Cancellation is the caller being torn down, not a sink failure. Swallowing it would
            // report this coroutine as having completed normally and break structured concurrency.
            if (e is CancellationException) throw e
            mutationLogger.error(e) {
                "Failed to record a mutation of \"" + wraps.serializer.descriptor.auditSerialName +
                    "\"; the change was already made and stands, unrecorded."
            }
        }
    }

    /**
     * Records one row per change.
     *
     * A change with neither side is not a change — that is how the database layer reports "nothing
     * matched" — and produces no row. Recording the *attempt* that matched nothing is the data access
     * log's job; this table holds changes.
     */
    private suspend fun recordChanges(operation: MutationOperation, changes: List<EntryChange<T>>) {
        if (changes.none { it.old != null || it.new != null }) return
        audit { modelId ->
            for (change in changes) {
                val old = change.old?.let(::encode)
                val new = change.new?.let(::encode)
                val identity = new ?: old ?: continue
                write(row(modelId, operation, idOf(identity), old, new))
            }
        }
    }

    /** The stand-in for [recordChanges] when a bulk call kept its cheap path. */
    private suspend fun recordSummary(operation: MutationOperation, affectedCount: Int) {
        if (affectedCount == 0) return
        audit { modelId ->
            write(row(modelId, operation, recordId = null, old = null, new = null, affectedCount = affectedCount))
        }
    }

    override suspend fun insert(models: Iterable<T>): List<T> {
        val inserted = wraps.insert(models)
        recordChanges(MutationOperation.Insert, inserted.map { EntryChange(old = null, new = it) })
        return inserted
    }

    override suspend fun replaceOne(
        condition: Condition<T>,
        model: T,
        orderBy: List<SortPart<T>>,
    ): EntryChange<T> {
        val change = wraps.replaceOne(condition, model, orderBy)
        recordChanges(MutationOperation.Replace, listOf(change))
        return change
    }

    /** Contract: "if a change was made", which [replaceOne] reports as a non-null `new`. */
    override suspend fun replaceOneIgnoringResult(
        condition: Condition<T>,
        model: T,
        orderBy: List<SortPart<T>>,
    ): Boolean {
        if (bulkDetail == BulkMutationDetail.SummaryOnly) {
            val changed = wraps.replaceOneIgnoringResult(condition, model, orderBy)
            recordSummary(MutationOperation.Replace, if (changed) 1 else 0)
            return changed
        }
        val change = wraps.replaceOne(condition, model, orderBy)
        recordChanges(MutationOperation.Replace, listOf(change))
        return change.new != null
    }

    override suspend fun upsertOne(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T,
    ): EntryChange<T> {
        val change = wraps.upsertOne(condition, modification, model)
        recordChanges(MutationOperation.Upsert, listOf(change))
        return change
    }

    /**
     * Contract: "if there was an existing element that matched the condition" — deliberately *not*
     * "if a change was made". [upsertOne] reports that as a non-null `old`; an insert returns
     * `EntryChange(null, model)` and so returns false here, which is the documented behaviour.
     */
    override suspend fun upsertOneIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
        model: T,
    ): Boolean {
        if (bulkDetail == BulkMutationDetail.SummaryOnly) {
            val existed = wraps.upsertOneIgnoringResult(condition, modification, model)
            // An upsert leaves a row behind either way, so the count is one; the return value says
            // which branch it took, not whether anything happened. The exception is a degenerate
            // call — a `Condition.Never`, or a modification that simplifies to nothing — which some
            // backends short-circuit without writing. Those over-report by one here, because a
            // summary row is all the information this mode kept. RecordEveryRow has no such gap.
            recordSummary(MutationOperation.Upsert, 1)
            return existed
        }
        val change = wraps.upsertOne(condition, modification, model)
        recordChanges(MutationOperation.Upsert, listOf(change))
        return change.old != null
    }

    override suspend fun updateOne(
        condition: Condition<T>,
        modification: Modification<T>,
        orderBy: List<SortPart<T>>,
    ): EntryChange<T> {
        val change = wraps.updateOne(condition, modification, orderBy)
        recordChanges(MutationOperation.Update, listOf(change))
        return change
    }

    /** Contract: "if a change was made", which [updateOne] reports as a non-null `new`. */
    override suspend fun updateOneIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
        orderBy: List<SortPart<T>>,
    ): Boolean {
        if (bulkDetail == BulkMutationDetail.SummaryOnly) {
            val changed = wraps.updateOneIgnoringResult(condition, modification, orderBy)
            recordSummary(MutationOperation.Update, if (changed) 1 else 0)
            return changed
        }
        val change = wraps.updateOne(condition, modification, orderBy)
        recordChanges(MutationOperation.Update, listOf(change))
        return change.new != null
    }

    override suspend fun updateMany(
        condition: Condition<T>,
        modification: Modification<T>,
    ): CollectionChanges<T> {
        val changes = wraps.updateMany(condition, modification)
        recordChanges(MutationOperation.Update, changes.changes)
        return changes
    }

    /**
     * Contract: "the number of entries affected", which [updateMany] reports as its change count.
     *
     * This is the upgrade that costs the most — every matched row is read and written back — and the
     * one [BulkMutationDetail.SummaryOnly] exists for.
     */
    override suspend fun updateManyIgnoringResult(
        condition: Condition<T>,
        modification: Modification<T>,
    ): Int {
        if (bulkDetail == BulkMutationDetail.SummaryOnly) {
            val affected = wraps.updateManyIgnoringResult(condition, modification)
            recordSummary(MutationOperation.Update, affected)
            return affected
        }
        val changes = wraps.updateMany(condition, modification)
        recordChanges(MutationOperation.Update, changes.changes)
        return changes.changes.size
    }

    override suspend fun deleteOne(condition: Condition<T>, orderBy: List<SortPart<T>>): T? {
        val deleted = wraps.deleteOne(condition, orderBy)
        recordChanges(MutationOperation.Delete, listOf(EntryChange(old = deleted, new = null)))
        return deleted
    }

    /** Contract: "whether any items were deleted", which [deleteOne] reports by returning the row. */
    override suspend fun deleteOneIgnoringOld(condition: Condition<T>, orderBy: List<SortPart<T>>): Boolean {
        if (bulkDetail == BulkMutationDetail.SummaryOnly) {
            val deleted = wraps.deleteOneIgnoringOld(condition, orderBy)
            recordSummary(MutationOperation.Delete, if (deleted) 1 else 0)
            return deleted
        }
        val deleted = wraps.deleteOne(condition, orderBy)
        recordChanges(MutationOperation.Delete, listOf(EntryChange(old = deleted, new = null)))
        return deleted != null
    }

    override suspend fun deleteMany(condition: Condition<T>): List<T> {
        val deleted = wraps.deleteMany(condition)
        recordChanges(MutationOperation.Delete, deleted.map { EntryChange(old = it, new = null) })
        return deleted
    }

    /** Contract: "the number of deleted items", which [deleteMany] reports as the rows it returns. */
    override suspend fun deleteManyIgnoringOld(condition: Condition<T>): Int {
        if (bulkDetail == BulkMutationDetail.SummaryOnly) {
            val affected = wraps.deleteManyIgnoringOld(condition)
            recordSummary(MutationOperation.Delete, affected)
            return affected
        }
        val deleted = wraps.deleteMany(condition)
        recordChanges(MutationOperation.Delete, deleted.map { EntryChange(old = it, new = null) })
        return deleted.size
    }
}
