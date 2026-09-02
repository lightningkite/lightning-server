package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Table
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** SHA-256, hex. Shared by the chain and by [verifyChain] so the two cannot disagree. */
public fun auditHash(input: String): String =
    MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray())
        .joinToString("") { b -> ((b.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

/**
 * The running head of one process's audit chain.
 *
 * Audit writes [fold] their record's hash in; the chain seals a [TotalLogEntry] once enough has
 * accumulated. Folding is cheap and in-memory, which is the point — a chain entry per disclosure row
 * would double the largest table in the system and serialise every audited write behind a chain head.
 * See `plans/audit-logging.md` 5.7.1.
 *
 * ## Sealing is driven by this object, not by a schedule
 * A [com.lightningkite.lightningserver.definition.ScheduledTask] is the obvious way to seal
 * periodically and is **wrong here**: schedules are coordinated by a distributed lock, so exactly one
 * instance runs each tick, while chains are per process and held in memory. A scheduled seal would
 * seal the winning instance's chain and leave every other instance accumulating records that are
 * never attested by anything. Sealing therefore happens on whichever instance owns the chain, driven
 * by its own volume.
 *
 * ## Unsealed work is unattested
 * Records folded but not yet sealed are covered by no entry, so they are present in the queryable log
 * and outside the chain. A quiet instance keeps a tail of them until its next fold or until [seal] is
 * called explicitly. Lowering [sealThreshold] narrows that window at the cost of more entries.
 */
public class AuditChain internal constructor(
    /** Identifies this process's chain. See [TotalLogEntry.chainId] for why it is per process. */
    public val chainId: String,
    /** Seal once this many records are pending. */
    private val sealThreshold: Int,
    private val nowMillis: () -> Long,
    private val write: suspend (TotalLogEntry) -> Unit,
) {
    private val mutex = Mutex()
    private var pending = StringBuilder()
    private var pendingCount = 0L
    private var sequence = 0L
    private var previousHash = ""

    /** Records folded but not yet sealed. */
    public suspend fun pendingCount(): Long = mutex.withLock { pendingCount }

    /**
     * Commits one audit record to the chain's next entry, sealing if enough has accumulated.
     *
     * Takes the record's hash rather than the record, so the chain never holds audit content: the
     * queryable log is the system of record and this only attests to it.
     */
    public suspend fun fold(recordHash: String) {
        mutex.withLock {
            pending.append(recordHash)
            pendingCount++
            if (pendingCount >= sealThreshold) sealLocked()
        }
    }

    /** Seals whatever is pending, if anything. Called at shutdown, and by tests. */
    public suspend fun seal(): TotalLogEntry? = mutex.withLock { sealLocked() }

    /**
     * Builds the next entry, writes it, and only then advances the chain.
     *
     * The ordering is the point. Advancing first and writing after would mean a failed write left
     * `sequence` and `previousHash` already moved on: the entry that was supposed to occupy that
     * position never exists, and every subsequent entry links to a hash no row carries, so the chain
     * reports itself permanently broken over a transient database error. Leaving the state untouched
     * means the records stay pending and are sealed into the next attempt instead.
     */
    @OptIn(ExperimentalUuidApi::class)
    private suspend fun sealLocked(): TotalLogEntry? {
        if (pendingCount == 0L) return null
        val entry = TotalLogEntry(
            _id = Uuid.generateV7NonMonotonicAt(kotlin.time.Instant.fromEpochMilliseconds(nowMillis())),
            chainId = chainId,
            sequence = sequence,
            previousHash = previousHash,
            contentHash = auditHash(pending.toString()),
            count = pendingCount,
            hash = "",
        ).let { it.copy(hash = auditHash(it.hashInput())) }

        write(entry)

        sequence++
        previousHash = entry.hash
        pending = StringBuilder()
        pendingCount = 0
        return entry
    }
}

/**
 * Reads a chain back and checks it links, using the same hash the writer used.
 *
 * A clean result means nothing was altered or removed *within* what remains. It does not mean the
 * chain is complete — see [verifyChain].
 */
context(server: ServerRuntime)
public suspend fun Table<TotalLogEntry>.verify(chainId: String): List<ChainBreak> =
    verifyChain(find(Condition.Always).toList().filter { it.chainId == chainId }, ::auditHash)
