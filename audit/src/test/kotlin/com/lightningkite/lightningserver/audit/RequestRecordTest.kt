package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.Unsafe
import com.lightningkite.services.data.UuidV7
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class RequestRecordTest {

    private fun origin(id: ExecutionId) = OriginRecord(
        _id = OriginRecord.ID(id),
        root = id,
        kind = OriginRecord.ExecutionKind.Http,
        location = "GET /x",
        request = null,
    )

    /**
     * RequestRecord carries no `at` column; the instant is derived from the version-7 `_id`. This
     * test pins that derivation so the two can never silently drift apart. V7 embeds whole
     * milliseconds, so the round trip is exact at that precision.
     */
    @OptIn(ExperimentalUuidApi::class)
    @Test
    fun `at derives the id's embedded timestamp`() {
        val instant = Instant.fromEpochMilliseconds(1_700_000_123_456)
        @OptIn(Unsafe::class)
        val id = ExecutionId(UuidV7.generateNonMonotonicAt(instant))

        val record = origin(id)

        assertEquals(instant, record.at)
    }

    /** A legacy (non-v7) id has no embedded timestamp; at must degrade to the epoch rather than throw. */
    @OptIn(ExperimentalUuidApi::class, Unsafe::class)
    @Test
    fun `a non-v7 id degrades to the epoch`() {
        val record = origin(ExecutionId(UuidV7.fromRaw(Uuid.random())))

        assertEquals(Instant.fromEpochMilliseconds(0), record.at)
    }
}
