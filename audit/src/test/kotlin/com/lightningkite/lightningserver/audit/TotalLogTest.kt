package com.lightningkite.lightningserver.audit

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chain's job is proving the log has not been edited since it was written. These tests pin both
 * halves of that: what it detects, and — just as important — what it provably does not.
 */
class TotalLogTest {

    private fun chainOf(vararg batches: List<String>): List<TotalLogEntry> = runBlocking {
        val chain = AuditChain("test-chain")
        var at = 1_700_000_000_000L
        batches.mapNotNull { batch ->
            batch.forEach { chain.fold(auditHash(it)) }
            at += 1_000
            chain.seal(at)
        }
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
        val chain = AuditChain("test-chain")
        assertEquals(null, chain.seal(1L))

        chain.fold(auditHash("a"))
        assertEquals(1L, chain.pendingCount())
        val sealed = chain.seal(2L)
        assertTrue(sealed != null)
        assertEquals(0L, chain.pendingCount())
        assertEquals(null, chain.seal(3L))
    }

    /**
     * The hashed bytes are built by hand rather than from a serialized form, so that adding a field
     * later cannot silently change what past hashes covered. This pins the layout, including the NUL
     * separator — a separator that could occur inside a field would let two different records hash
     * identically, and the serialized conditions in a DataAccessRecord contain spaces and commas.
     */
    @Test
    fun `the hash input is a stable, hand-built layout`() {
        val entry = TotalLogEntry(
            _id = kotlin.uuid.Uuid.random(),
            chainId = "c",
            sequence = 7,
            previousHash = "prev",
            contentHash = "content",
            count = 3,
            hash = "ignored",
        )

        assertEquals(listOf("c", "7", "prev", "content", "3").joinToString("\u0000"), entry.hashInput())
    }
}
