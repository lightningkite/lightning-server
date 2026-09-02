package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The kind of database operation a [DataAccessRecord] describes. */
@Serializable
public enum class DataAccessOperation {
    Find,
    Count,
    Aggregate,
    GroupCount,
    GroupAggregate,
    Insert,
    Update,
    Replace,
    Delete,
}

/**
 * One query against an audited model — what the code asked for, whoever asked.
 *
 * This is the layer that closes the aggregation and oracle channels the disclosure log structurally
 * cannot see. `groupCount(groupBy = ssn)` returns distinct field values and discloses no *record*, so
 * it produces no [DisclosureRecord] at all; `find(ssn eq "X")` returning nothing leaks the same bit
 * while disclosing nothing; a sort plus `skip` walks values without ever matching one. Recording the
 * condition makes all three the same shape of evidence — a binary search appears as thousands of
 * rows whose conditions walk a value.
 *
 * Sits at the database layer rather than the typed layer precisely so it also sees the privileged
 * internal reads that never reach a user. See `plans/audit-logging.md` sections 6.1 and 6.2.
 *
 * ## The query is stored as text
 * `Condition<T>` and `Modification<T>` are serializable, but only against the model's own serializer,
 * and this one table holds rows for every audited model. A generic record type would mean a table per
 * model. What that gives up is querying *inside* a recorded condition; an investigation reads these
 * rows, it does not join on their contents.
 *
 * ## What it does not capture
 * A recorded condition says a bulk read happened, not what came back. For a `groupCount` over a
 * sensitive field the log says "someone enumerated this field" without the values, because by
 * construction there are no record ids to name. A deployment that cannot accept that should deny the
 * grouping through permissions rather than expect the framework to forbid it.
 *
 * @property requestId Joins to [RequestRecord], and matches the id a [DisclosureRecord] from the same
 *   execution carries. For a WebSocket this names the socket, not the phase.
 * @property executionId The execution that actually issued the query. Recorded alongside [requestId]
 *   because that one deliberately blurs a socket's phases together; this is what places a query at a
 *   specific message on a long-lived connection.
 * @property modelId The audited model, from the same registry [DisclosureRecord] uses.
 * @property condition The `Condition<T>` applied, serialized with the model's serializer.
 * @property sort The ordering applied, where the operation takes one. A sort is an oracle too.
 * @property modification The change applied, for write operations.
 * @property groupBy The field path an aggregation grouped on — the thing that makes a group query an
 *   enumeration of that field's values.
 */
@GenerateDataClassPaths
@Serializable
public data class DataAccessRecord(
    override val _id: Uuid,
    @Index val requestId: Uuid,
    @Index val executionId: Uuid,
    @Index val modelId: Int,
    val operation: DataAccessOperation,
    val condition: String,
    val sort: String? = null,
    val modification: String? = null,
    val groupBy: String? = null,
) : HasId<Uuid> {
    /** When the query ran, derived from the version-7 [_id]. See [RequestRecord] for why it lives there. */
    @OptIn(ExperimentalUuidApi::class)
    public val at: Instant
        get() = Instant.fromEpochMilliseconds(_id.epochMilliseconds)

    public companion object
}
