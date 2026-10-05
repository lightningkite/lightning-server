package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.TypedId
import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/** The kind of change a [MutationRecord] describes. */
@Serializable
public enum class MutationOperation {
    Insert,
    Update,
    Replace,
    Upsert,
    Delete,
}

/**
 * One change to one audited record — what it was, what it became, and which execution did it.
 *
 * The question this answers is "who changed this record, and to what", which no other layer can. A
 * [DataAccessRecord] captures the `Modification` that was *submitted*; that is intent, not effect. It
 * says nothing about which rows a condition actually matched, and a modification like
 * `count assign count + 1` does not name a resulting value at all. This layer records after the fact,
 * per affected row, with both sides of the change.
 *
 * ## A separate table from the data access log, deliberately
 * Looking for tampering and looking for query abuse are unrelated investigations with unrelated
 * volumes, and each should be installable without paying for the other. Sharing a table would also
 * mean one schema serving two record shapes, most columns null in either direction.
 *
 * ## Both sides are stored as text
 * As with [DataAccessRecord], one table holds rows for every audited model, and a model-typed column
 * would mean a table per model. What that gives up is querying *inside* a recorded value; an
 * investigation reads these rows, it does not join on their contents.
 *
 * @property requestId The request record for the execution that made the change, where it has one.
 *   Set for `Http` and `WebSocket` initiators and null for everything else, because
 *   `RequestRecordInterceptor` is an http/websocket interceptor and writes no row for a task, a
 *   schedule tick, startup, pre-deploy, or a directly built runtime. Storing the execution id there
 *   anyway would produce an id that joins to nothing, which is worse than an honest null.
 * @property attributedTo The request record that names **who is responsible**, and the column to
 *   start an investigation from. Never null, and it resolves for indirect work where [requestId]
 *   cannot: a change made inside a task carries the anchor of whatever launched it.
 *
 *   Deliberately not the same question as [rootExecutionId]. The framework creates inner executions
 *   that carry their own credentials — a `/meta/bulk` sub-request and a multiplexed sub-socket both
 *   take per-sub query parameters, and `SessionManager.read` falls back to the `Authorization` and
 *   `jwt` query parameters — so an anonymous carrier can dispatch an authenticated inner execution.
 *   The head of the causal chain is then the carrier, which names nobody, while the person is named
 *   on the inner execution's row. Both shapes are pinned by tests in this module.
 * @property executionId The execution that actually made the change. For a socket this is the phase,
 *   where [requestId] deliberately names the whole socket.
 * @property causedBy The execution that caused this one, or null if it started here.
 * @property rootExecutionId The head of the causal chain — how a mutation made three tasks deep
 *   chains back to whatever started it, in one indexed lookup rather than a recursive walk.
 *
 *   This is a *causal* key, not an attribution key: it answers "what set this off", and the answer
 *   can be an execution that authenticated nobody. Use [attributedTo] to reach the person.
 * @property initiatorKind The initiator's `@SerialName` discriminator — "http", "ws", "task",
 *   "schedule", "startup", "predeploy", "direct". Indexed and denormalised out of [initiator] so that
 *   "every mutation not made by a request" is a query rather than a scan of JSON.
 * @property initiator The whole `Execution`, serialized. Carries the endpoint, socket phase or task
 *   location that [initiatorKind] alone cannot.
 * @property modelType The audited model, from the same registry [DisclosureRecord] uses.
 * @property recordId The changed row's `_id`, as text — the models this can wrap are not all keyed by
 *   `Uuid`. Null only on a summary row, where by construction no single row is named.
 * @property old The previous value, serialized. Null for an insert, and for the inserting branch of
 *   an upsert.
 * @property new The resulting value, serialized. Null for a delete. Note that for an update this is
 *   the database layer's *prediction* — see `Table.updateOne` — computed by applying the modification
 *   to the row that was read, not a re-read of the row.
 * @property affectedCount How many rows the call changed. Set only on a summary row; a per-row row
 *   describes exactly one change and does not need it.
 */
@GenerateDataClassPaths
@Serializable
public data class MutationRecord(
    override val _id: ID,
    @Index val requestId: RequestRecord.ID? = null,
    @Index val attributedTo: RequestRecord.ID,
    @Index val executionId: ExecutionId,
    val causedBy: ExecutionId? = null,
    @Index val rootExecutionId: ExecutionId,
    @Index val initiatorKind: String,
    val initiator: String,
    @Index val modelType: ModelTypeId,
    @Index val recordId: String? = null,
    val operation: MutationOperation,
    val old: String? = null,
    val new: String? = null,
    val affectedCount: Int? = null,
) : HasId<MutationRecord.ID> {
    @Serializable
    @JvmInline
    public value class ID(override val raw: UuidV7) : TypedId<UuidV7, ID>

    /** When the change happened, derived from the version-7 [_id]. See [RequestRecord] for why it lives there. */
    public val at: Instant
        get() = _id.timestamp()

    public companion object
}
