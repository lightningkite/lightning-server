package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.data.IndexSet
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * One record disclosed to one client, and exactly which of its fields carried a value.
 *
 * ## One row per record, deliberately
 *
 * An earlier design grouped records that shared a disclosed-field set into a single row with a list
 * of ids, which collapsed a ten-thousand-row query into one or two rows. That was rejected: this is
 * an audit log, and "most of these records were disclosed the same way" is not a thing an audit log
 * gets to say. Every disclosure is its own row.
 *
 * The cost is paid down in the row itself rather than by grouping:
 *
 * - Request-constant data — who asked, from where, when, through which endpoint — lives once in the
 *   access log and is referenced here by [requestId] alone. It is never repeated per row.
 * - [recordId] is a `Uuid`, which every backend stores as sixteen bytes. This is why [Audited] is
 *   restricted to models keyed by `Uuid`: a stringly-typed identifier column is both larger and, in
 *   most engines, stored and indexed poorly.
 * - There is no parent request id. A sub-request's parentage is recorded once in the request log,
 *   so repeating it on every disclosure would be storing a join key twice.
 *
 * @property requestId Correlates to the access log entry holding who asked, from where, and when —
 *   including, for a multiplexed sub-request, which request carried it.
 * @property modelId The audited model's permanent id, from [AuditModelRegistration].
 * @property recordId The `_id` of the disclosed record.
 */
@IndexSet(fields = ["modelId", "recordId"], name = "byRecord")
@GenerateDataClassPaths
@Serializable
public data class DisclosureRecord(
    override val _id: Uuid,
    @Index val requestId: String,
    val modelId: Int,
    /** Bits 0..31 of the disclosed-field set. See [FieldBits]. */
    val fields0: Int = 0,
    /** Bits 32..63 of the disclosed-field set. */
    val fields1: Int = 0,
    /** Bits 64..95 of the disclosed-field set. */
    val fields2: Int = 0,
    /** Bits 96..127 of the disclosed-field set. */
    val fields3: Int = 0,
    val recordId: Uuid,
) : HasId<Uuid> {
    public val fields: FieldBits get() = FieldBits.ofColumns(fields0, fields1, fields2, fields3)

    public companion object
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

/**
 * One condition per column that [indices] actually touches.
 *
 * The columns are independent `Condition<Int>`s, so a query spanning several of them is a
 * combination of per-column conditions rather than a single one.
 */
private inline fun columnConditions(
    indices: Iterable<Int>,
    columnCondition: (mask: Int) -> Condition<Int>,
): List<Condition<DisclosureRecord>> {
    val bits = FieldBits.of(indices)
    return (0 until FieldBits.COLUMNS)
        .filter { bits.column(it) != 0 }
        .map { Condition.OnField(disclosureFieldColumns[it], columnCondition(bits.column(it))) }
}

private val disclosureFieldColumns = listOf(
    DisclosureRecord_fields0,
    DisclosureRecord_fields1,
    DisclosureRecord_fields2,
    DisclosureRecord_fields3,
)
