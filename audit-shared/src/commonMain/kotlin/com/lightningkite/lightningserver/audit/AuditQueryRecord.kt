package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.TypedId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * Represents one query against an [AuditedModel]: what the code asked for, under which origin.
 *
 * This catches reads that leak information without disclosing a record, which a [DisclosureRecord] cannot.
 * A `groupCount` over a sensitive field returns its distinct values, a `find` that matches nothing still
 * confirms a guess, and a sort with a moving `skip` walks values one at a time. Each shows up here as its
 * condition, ordering and paging.
 *
 * The query parts are stored as JSON text, encoded with the model's serializer, so one table can hold
 * queries against every audited model.
 *
 * @property origin The request or other origin the query ran under.
 * @property modelType The audited model queried, as assigned by the `AuditRegistry`.
 * @property condition The condition applied.
 * @property sort The ordering applied, or `null` if there was none.
 * @property modification The modification applied, for an update.
 * @property groupBy The field path a group query grouped on, or the vector field of a similarity search.
 * @property skip The offset applied, for a find.
 * @property limit The page size applied for a find, or the number of models inserted for an insert.
 * @property fields The fields a partial find asked for.
 * @property aggregate The aggregate applied, or the parameters of a similarity search.
 */
@GenerateDataClassPaths
@Serializable
public data class AuditQueryRecord(
    override val _id: ID,
    @SerialName("ogn") @Index val origin: OriginRecord.ID,
    @SerialName("op") val operation: Operation,
    @SerialName("mt") @Index val modelType: ModelTypeId,
    val condition: String,
    val sort: String? = null,
    val modification: String? = null,
    val groupBy: String? = null,
    val skip: Int? = null,
    val limit: Int? = null,
    val fields: String? = null,
    val aggregate: String? = null,
) : HasId<AuditQueryRecord.ID> {
    @Serializable
    @JvmInline
    public value class ID(override val raw: UuidV7) : TypedId<UuidV7, ID>

    /** The kind of query an [AuditQueryRecord] describes. */
    @Serializable
    public enum class Operation {
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

    /** Returns when the query ran, taken from the version 7 [_id]. */
    public val at: Instant
        get() = _id.timestamp()
}
