package com.lightningkite.lightningserver.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class DisclosureRecordTest {

    private fun record(id: Uuid) = DisclosureRecord(
        _id = id,
        requestId = Uuid.random(),
        modelId = 1,
        recordId = Uuid.random(),
    )

    /**
     * Like RequestRecord, a disclosure row carries no `at` column and derives its instant from the
     * version-7 `_id`. This matters more here than there: `requestId` points at a socket's row
     * rather than at the phase that disclosed, so this is the only thing that places a disclosure on
     * a long-lived connection at a specific moment.
     */
    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `at derives the id's embedded timestamp`() {
        val instant = Instant.fromEpochMilliseconds(1_700_000_123_456)
        assertEquals(instant, record(Uuid.generateV7NonMonotonicAt(instant)).at)
    }

    /** A non-v7 id has no embedded timestamp; at degrades to the epoch rather than inventing one. */
    @Test
    fun `a non-v7 id degrades to the epoch`() {
        assertEquals(Instant.fromEpochMilliseconds(0), record(Uuid.random()).at)
    }

    /** Ordering by id is ordering by time — the property the append-mostly table relies on. */
    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `v7 ids sort in mint order`() {
        val base = 1_700_000_000_000
        val ids = (0..20).map { Uuid.generateV7NonMonotonicAt(Instant.fromEpochMilliseconds(base + it * 1_000L)) }

        assertEquals(ids, ids.shuffled().sortedBy { it.toString() })
    }
}
