package com.lightningkite.lightningserver.audit

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The chain's job is proving the log has not been edited since it was written. These tests pin both
 * halves of that: what it detects, and — just as important — what it provably does not.
 */
class TotalLogTest {

    /** A chain that never auto-seals, so each batch below is sealed explicitly. */
    private fun manualChain(
        at: LongArray = longArrayOf(1_700_000_000_000L),
        write: suspend (TotalLogEntry) -> Unit = {},
    ) = AuditChain(
        chainId = "test-chain",
        sealThreshold = Int.MAX_VALUE,
        nowMillis = { at[0].also { at[0] = it + 1_000 } },
        write = write,
    )

    private fun chainOf(vararg batches: List<String>): List<TotalLogEntry> = runBlocking {
        val written = mutableListOf<TotalLogEntry>()
        val chain = manualChain(write = { written.add(it) })
        batches.forEach { batch ->
            batch.forEach { chain.fold(auditHash(it)) }
            chain.seal()
        }
        written
    }

    @Test
    fun `a chain of sealed entries verifies`() {
        val entries = chainOf(listOf("a", "b"), listOf("c"), listOf("d", "e", "f"))

        assertEquals(3, entries.size)
        assertEquals(emptyList(), verifyChain(entries, ::auditHash))
        assertEquals(listOf(2L, 1L, 3L), entries.map { it.count })
    }

    @Test
    fun `each entry links to the one before it`() {
        val entries = chainOf(listOf("a"), listOf("b"), listOf("c"))

        assertEquals("", entries[0].previousHash)
        assertEquals(entries[0].hash, entries[1].previousHash)
        assertEquals(entries[1].hash, entries[2].previousHash)
        assertEquals(listOf(0L, 1L, 2L), entries.map { it.sequence })
    }

    /** Editing a sealed entry's content invalidates its own hash. */
    @Test
    fun `an altered entry is detected`() {
        val entries = chainOf(listOf("a"), listOf("b"), listOf("c")).toMutableList()
        entries[1] = entries[1].copy(contentHash = auditHash("something else"))

        assertEquals(listOf(ChainBreak.Altered(1L)), verifyChain(entries, ::auditHash))
    }

    /** Removing an entry from the middle breaks the link across the gap. */
    @Test
    fun `a removed entry is detected`() {
        val entries = chainOf(listOf("a"), listOf("b"), listOf("c"))
        val withoutMiddle = listOf(entries[0], entries[2])

        val breaks = verifyChain(withoutMiddle, ::auditHash)
        assertTrue(breaks.any { it is ChainBreak.Broken }, "removing an entry went undetected: $breaks")
    }

    /**
     * The limitation that motivates external anchoring, pinned so it cannot be forgotten: a chain
     * cut off at its end verifies clean, because the surviving prefix is internally consistent.
     * Nothing inside the system can tell how long the chain should have been.
     */
    @Test
    fun `a truncated chain still verifies, which is why anchoring exists`() {
        val entries = chainOf(listOf("a"), listOf("b"), listOf("c"))

        assertEquals(
            emptyList(),
            verifyChain(entries.dropLast(1), ::auditHash),
            "truncation is expected to be undetectable here; if this now fails, 5.7.1 needs updating",
        )
    }

    /** An idle server must not grow entries attesting to nothing, or length stops meaning anything. */
    @Test
    fun `sealing with nothing pending produces no entry`() = runBlocking {
        val chain = manualChain()
        assertEquals(null, chain.seal())

        chain.fold(auditHash("a"))
        assertEquals(1L, chain.pendingCount())
        assertTrue(chain.seal() != null)
        assertEquals(0L, chain.pendingCount())
        assertEquals(null, chain.seal())
    }

    /** Volume-driven, because a scheduled seal is distributed-locked and would only seal one instance. */
    @Test
    fun `the chain seals itself once enough is pending`() = runBlocking {
        val written = mutableListOf<TotalLogEntry>()
        val chain = AuditChain("test-chain", sealThreshold = 3, nowMillis = { 1L }, write = { written.add(it) })

        repeat(2) { chain.fold(auditHash("x")) }
        assertEquals(0, written.size, "sealed before reaching the threshold")

        chain.fold(auditHash("x"))
        assertEquals(1, written.size, "did not seal on reaching the threshold")
        assertEquals(3L, written.single().count)
        assertEquals(0L, chain.pendingCount())
    }

    /**
     * A failed write must not advance the chain. Advancing first would leave `sequence` and
     * `previousHash` moved past an entry that no row carries, so every later entry would link to a
     * hash that does not exist and the chain would report itself permanently broken over what may
     * have been a transient database error.
     */
    @Test
    fun `a failed write leaves the chain where it was`() = runBlocking {
        var failNext = true
        val written = mutableListOf<TotalLogEntry>()
        val chain = AuditChain(
            chainId = "test-chain",
            sealThreshold = Int.MAX_VALUE,
            nowMillis = { 1L },
            write = { if (failNext) throw RuntimeException("sink down") else written.add(it) },
        )

        chain.fold(auditHash("a"))
        try {
            chain.seal()
            fail("the write failure was swallowed")
        } catch (_: RuntimeException) {
        }
        assertEquals(1L, chain.pendingCount(), "the record was dropped by a failed seal")

        failNext = false
        chain.fold(auditHash("b"))
        val recovered = chain.seal()
        assertTrue(recovered != null)
        assertEquals(0L, recovered.sequence, "the chain skipped a sequence number over a failed write")
        assertEquals(2L, recovered.count, "the record pending at the time of failure was lost")
        assertEquals(emptyList(), verifyChain(written, ::auditHash))
    }

    /**
     * The hashed bytes are built by hand rather than from a serialized form, so that adding a field
     * later cannot silently change what past hashes covered. This pins the layout, including the NUL
     * separator — a separator that could occur inside a field would let two different records hash
     * identically, and the serialized conditions in a DataAccessRecord contain spaces and commas.
     *
     * `_id` is covered too. Leaving it out made the entry's seal time — which derives from the v7 id
     * — freely rewritable with the chain still verifying clean.
     */
    @Test
    fun `the hash input is a stable, hand-built layout`() {
        val id = kotlin.uuid.Uuid.parse("00000000-0000-7000-8000-000000000001")
        val entry = TotalLogEntry(
            _id = id,
            chainId = "c",
            sequence = 7,
            previousHash = "prev",
            contentHash = "content",
            count = 3,
            hash = "ignored",
        )

        assertEquals(
            listOf(id.toString(), "c", "7", "prev", "content", "3").joinToString("\u0000"),
            entry.hashInput(),
        )
    }
}
