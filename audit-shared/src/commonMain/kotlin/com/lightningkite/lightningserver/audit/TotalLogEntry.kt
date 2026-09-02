package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * One sealed link in an audit chain: proof that everything committed to it is unaltered since.
 *
 * Separate from the queryable logs on purpose. Their job is investigation, this one's is proving
 * nothing was edited, and keeping the two apart leaves the highest-volume table free of chain columns
 * it would never read. See `plans/audit-logging.md` 5.7 and 5.7.1.
 *
 * ## What a chain proves, and what it does not
 * Altering or removing any sealed entry breaks the link to its successor, which [verifyChain]
 * detects. **Truncating a chain at its end does not** — the surviving prefix is internally
 * consistent — and closing that requires anchoring a head somewhere the operator does not control.
 * Nothing here addresses fabrication at write time: a false record written through the normal path
 * chains perfectly well. The chain proves the log has not been edited since it was written, not that
 * it was true when written.
 *
 * @property chainId The process that owns this chain — its server id and boot time. One chain per
 *   process, because a chain shared between instances would need them to agree on [sequence], which
 *   is a distributed lock on the hot path of every audited write. The cost is that ordering between
 *   two chains is not established.
 * @property sequence Position within [chainId], from 0, with no gaps.
 * @property previousHash The [hash] of the entry at `sequence - 1`, or the empty string at 0.
 * @property contentHash Fold of the hash of every audit record sealed into this entry.
 * @property count How many records [contentHash] covers. Recorded so a verifier can see the shape of
 *   what was sealed without holding the records.
 */
@GenerateDataClassPaths
@Serializable
public data class TotalLogEntry(
    override val _id: Uuid,
    @Index val chainId: String,
    val sequence: Long,
    val previousHash: String,
    val contentHash: String,
    val count: Long,
    val hash: String,
) : HasId<Uuid> {
    /** When this link was sealed, derived from the version-7 [_id]. */
    @OptIn(ExperimentalUuidApi::class)
    public val at: Instant
        get() = Instant.fromEpochMilliseconds(_id.epochMilliseconds)

    public companion object
}

/** Why a chain failed verification. */
public sealed interface ChainBreak {
    /** The entry's own [TotalLogEntry.hash] does not match its contents: it was edited in place. */
    public data class Altered(val sequence: Long) : ChainBreak

    /** [TotalLogEntry.previousHash] does not name the preceding entry: something between was removed. */
    public data class Broken(val sequence: Long) : ChainBreak

    /** Sequence numbers are not contiguous from 0. */
    public data class Gap(val expected: Long, val found: Long) : ChainBreak
}

/**
 * Checks that [entries] form an unbroken chain, in order, from sequence 0.
 *
 * Returns the breaks found, empty when the chain verifies. Note what a clean result does *not* say:
 * a chain truncated at its end verifies, because the surviving prefix is consistent. Only an external
 * anchor can establish how long the chain should have been.
 */
public fun verifyChain(entries: List<TotalLogEntry>, hasher: (String) -> String): List<ChainBreak> {
    val breaks = mutableListOf<ChainBreak>()
    var expectedPrevious = ""
    entries.sortedBy { it.sequence }.forEachIndexed { index, entry ->
        if (entry.sequence != index.toLong()) breaks.add(ChainBreak.Gap(index.toLong(), entry.sequence))
        if (entry.hash != hasher(entry.hashInput())) breaks.add(ChainBreak.Altered(entry.sequence))
        else if (entry.previousHash != expectedPrevious) breaks.add(ChainBreak.Broken(entry.sequence))
        expectedPrevious = entry.hash
    }
    return breaks
}

/**
 * Separator for hashed field layouts.
 *
 * NUL rather than a space or a comma because several hashed fields are free text — a serialized
 * `Condition` is JSON and contains both — and a separator that can occur inside a field makes the
 * layout ambiguous: two different records could hash to the same bytes. NUL cannot appear in any of
 * them.
 */
private const val FIELD_SEPARATOR = "\u0000"

/**
 * The exact bytes an entry's [TotalLogEntry.hash] covers.
 *
 * Built by hand rather than by serializing the entry, so that adding a field to [TotalLogEntry] later
 * cannot silently change what historical hashes were computed over and invalidate every chain.
 */
public fun TotalLogEntry.hashInput(): String =
    listOf(chainId, sequence.toString(), previousHash, contentHash, count.toString())
        .joinToString(FIELD_SEPARATOR)

/**
 * The bytes of an audit record that its chain entry attests to.
 *
 * Built by hand for the same reason as [hashInput]: hashing a serialized form would mean a later
 * field addition silently changes what past hashes covered, and every historical chain would stop
 * verifying against records that were never touched.
 */
public fun DisclosureRecord.chainInput(): String =
    listOf(
        _id.toString(), requestId.toString(), modelId.toString(),
        fields0.toString(), fields1.toString(), recordId.toString(),
    ).joinToString(FIELD_SEPARATOR)

/** As [chainInput], for a query record. */
public fun DataAccessRecord.chainInput(): String =
    listOf(
        _id.toString(), requestId.toString(), executionId.toString(), modelId.toString(),
        operation.name, condition, sort ?: "", modification ?: "", groupBy ?: "",
    ).joinToString(FIELD_SEPARATOR)
