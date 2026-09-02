package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.insertOne
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
 * Audit writes [fold] their record's hash in; [seal] periodically turns everything folded since the
 * last seal into a [TotalLogEntry]. Folding is cheap and in-memory, which is the point — a chain
 * entry per disclosure row would double the largest table in the system and serialise every audited
 * write behind a chain head. See `plans/audit-logging.md` 5.7.1.
 *
 * ## Unsealed work is lost if the process dies
 * Records folded but not yet sealed are covered by no entry, so a crash between seals leaves those
 * records outside the chain — present in the queryable log, but unattested. Sealing more often
 * narrows that window at the cost of more entries. This is a deliberate consequence of batching, not
 * an oversight, and it is why [seal] is also called at shutdown.
 */
public class AuditChain internal constructor(
    /** Identifies this process's chain. See [TotalLogEntry.chainId] for why it is per process. */
    public val chainId: String,
) {
    private val mutex = Mutex()
    private var pending = StringBuilder()
    private var pendingCount = 0L
    private var sequence = 0L
    private var previousHash = ""

    /** Records folded since the last seal. Exposed for tests and for shutdown to decide whether to seal. */
    public suspend fun pendingCount(): Long = mutex.withLock { pendingCount }

    /**
     * Commits one audit record to the chain's next entry.
     *
     * Takes the record's hash rather than the record so the chain never holds audit content: the
     * queryable log is the system of record, this only attests to it.
     */
    public suspend fun fold(recordHash: String) {
        mutex.withLock {
            pending.append(recordHash)
            pendingCount++
        }
    }

    /**
     * Seals everything folded since the previous seal into a new entry, or returns null when there is
     * nothing pending.
     *
     * Empty seals are skipped so that an idle server does not grow a chain of entries attesting to
     * nothing, which would make the chain's length useless as a truncation signal.
     */
    @OptIn(ExperimentalUuidApi::class)
    public suspend fun seal(nowMillis: Long): TotalLogEntry? = mutex.withLock {
        if (pendingCount == 0L) return@withLock null
        val contentHash = auditHash(pending.toString())
        val entry = TotalLogEntry(
            _id = Uuid.generateV7NonMonotonicAt(kotlin.time.Instant.fromEpochMilliseconds(nowMillis)),
            chainId = chainId,
            sequence = sequence,
            previousHash = previousHash,
            contentHash = contentHash,
            count = pendingCount,
            hash = "",
        ).let { it.copy(hash = auditHash(it.hashInput())) }
        sequence++
        previousHash = entry.hash
        pending = StringBuilder()
        pendingCount = 0
        entry
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
