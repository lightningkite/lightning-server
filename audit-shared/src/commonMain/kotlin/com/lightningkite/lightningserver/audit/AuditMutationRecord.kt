package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.TypedId
import kotlinx.serialization.SerialName
import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@GenerateDataClassPaths
@Serializable
public data class AuditMutationRecord(
    override val _id: ID,
    @SerialName("ogn") @Index val origin: OriginRecord.ID,
    @SerialName("op") val operation: Operation,
    @SerialName("mt") val modelType: ModelTypeId,
    @SerialName("mid") @Index val modelId: Uuid,
    val data: JsonOfPartial? = null,
) : HasId<AuditMutationRecord.ID> {
    @Serializable
    @JvmInline
    public value class ID(override val raw: UuidV7) : TypedId<UuidV7, ID>

    public typealias JsonOfPartial = String

    /** The kind of change a [AuditMutationRecord] describes. */
    @Serializable
    public enum class Operation {
        Create,
        Modify,
        Delete
    }

    /** Returns when the change happened, taken from the version 7 [_id]. */
    public val at: Instant
        get() = _id.timestamp()
}
