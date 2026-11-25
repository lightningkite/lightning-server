package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.services.data.Unsafe
import com.lightningkite.services.data.UuidV7
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

// OriginRecording keeps queued records in the execution's context, which is written in the wire format.
class OriginRecordWireFormatTest {
    @OptIn(Unsafe::class)
    @Test
    fun originRecordRoundTrips() {
        val wire = Serialization().kotlinWireFormat
        val id = ExecutionId(UuidV7.generate())
        val serializer = OriginRecord.serializer(OriginRecord.StandardRequestInfo.serializer())
        for (kind in OriginRecord.ExecutionKind.entries) {
            val record = OriginRecord(
                _id = OriginRecord.ID(id),
                parent = ExecutionId(UuidV7.generate()),
                root = id,
                kind = kind,
                location = "GET /x",
                request = OriginRecord.StandardRequestInfo(
                    subjectId = Uuid.random(),
                    sessionId = null,
                    sourceIp = "127.0.0.1",
                    engineRequestId = "engine",
                    upstreamRequestId = null,
                ),
            )
            assertEquals(record, wire.decodeFromByteArray(serializer, wire.encodeToByteArray(serializer, record)))
        }
    }
}
