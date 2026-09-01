package com.lightningkite.lightningserver.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class RequestRecordTest {

    /**
     * RequestRecord carries no `at` column; the instant is derived from the version-7 `_id`. This
     * test pins that derivation so the two can never silently drift apart. V7 embeds whole
     * milliseconds, so the round trip is exact at that precision.
     */
    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `at derives the id's embedded timestamp`() {
        val instant = Instant.fromEpochMilliseconds(1_700_000_123_456)
        val id = Uuid.generateV7NonMonotonicAt(instant)

        val record = RequestRecord(
            _id = id,
            rootExecutionId = id,
            sourceIp = "1.2.3.4",
            endpoint = "/x",
            method = "GET",
        )

        assertEquals(instant, record.at)
    }

    /** A legacy (non-v7) id has no embedded timestamp; at must degrade to the epoch rather than throw. */
    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `a non-v7 id degrades to the epoch`() {
        val v4 = Uuid.random()
        val record = RequestRecord(
            _id = v4,
            rootExecutionId = v4,
            sourceIp = "1.2.3.4",
            endpoint = "/x",
            method = "GET",
        )

        assertEquals(Instant.fromEpochMilliseconds(0), record.at)
    }
}
