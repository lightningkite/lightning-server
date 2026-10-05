package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.data.IndexSet
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.Condition
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
    @SerialName("qid") @Index val requestId: Uuid,
    @SerialName("rt") val recordType: Int,
    @SerialName("rid") val recordId: Uuid,
    @SerialName("d") val disclosed: FieldIndices,
) : HasId<DisclosureRecord.ID> {
    @Serializable
    @JvmInline
    public value class ID(override val raw: UuidV7) : TypedId<UuidV7, ID>

    public val at: Instant
        get() = _id.raw.timestamp()
}



/**
 * Disclosures whose field set contains **every** one of [indices] — "which requests disclosed both
 * the SSN and the date of birth?".
 *
 * With no indices this is [Condition.Always], since every set vacuously contains none of them.
 */
public fun disclosedAll(indices: Iterable<Int>): Condition<DisclosureRecord> =
    columnConditions(indices) { Condition.IntBitsSet(it) }
        .let { if (it.isEmpty()) Condition.Always else Condition.And(it) }

/**
 * Disclosures whose field set contains **at least one** of [indices] — "which requests disclosed
 * anything sensitive?".
 *
 * With no indices this is [Condition.Never].
 */
public fun disclosedAny(indices: Iterable<Int>): Condition<DisclosureRecord> =
    columnConditions(indices) { Condition.IntBitsAnySet(it) }
        .let { if (it.isEmpty()) Condition.Never else Condition.Or(it) }

private inline fun columnConditions(
    indices: Iterable<Int>,
    columnCondition: (mask: Int) -> Condition<Int>,
): List<Condition<DisclosureRecord>> {
    val bits = FieldIndices.from(indices)
//    return (0 until FieldIndices.SEGMENTS)
//        .filter { bits.segment(it) != 0 }
//        .map { Condition.OnField(disclosureFieldColumns[it], columnCondition(bits.segment(it))) }
    TODO()
}

