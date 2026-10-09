package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.data.IndexSet
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.TypedId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlin.uuid.Uuid

@IndexSet(fields = ["rt", "rid"], name = "DisclosureRecord_byRecord")
@GenerateDataClassPaths
@Serializable
public data class DisclosureRecord(
    override val _id: ID,
    @SerialName("o") @Index val origin: OriginRecord.ID,
    @SerialName("rt") val recordType: ModelTypeId,
    @SerialName("rid") val recordId: Uuid,
    @SerialName("d") val disclosed: FieldIdentifierSet,
) : HasId<DisclosureRecord.ID> {
    @Serializable
    @JvmInline
    public value class ID(override val raw: UuidV7) : TypedId<UuidV7, ID>

    public val at: Instant
        get() = _id.timestamp()
}


