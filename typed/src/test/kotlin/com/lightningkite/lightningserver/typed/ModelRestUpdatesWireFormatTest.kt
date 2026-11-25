package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.database.*
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

// The updates socket's state, and the changes its topics carry, are written in the server's kotlinWireFormat on AWS.
class ModelRestUpdatesWireFormatTest {
    object TestServer : ServerBuilder()

    @Test
    fun socketStateWithConditionsAndMask() = TestServer.test(settings = {}) {
        val wire = engine.internalSerialization.kotlinWireFormat
        val condition = Condition<Sample> { (it.name eq "A") and (it.note inside setOf("x", "y")) or (it.name neq "B") }
        val data = ModelRestUpdatesWebSocketData<Sample, String>(
            user = null,
            clientCondition = Condition.Always,
            condition = condition,
            mask = Mask(listOf(Condition<Sample> { it.name eq "A" } to Modification { it.note assign "" })),
            permissionsCheckedAt = Instant.fromEpochSeconds(1_700_000_000),
            topics = setOf("model/updates/hash/3"),
        )
        val storage = ApiWebSocketStorage(MediaType("application", "json"), data, respondToPings = false)
        val serializer = ApiWebSocketStorage.serializer(ModelRestUpdatesWebSocketData.serializer(Sample.serializer(), String.serializer()))
        assertEquals(storage, wire.decodeFromByteArray(serializer, wire.encodeToByteArray(serializer, storage)))
    }

    @Test
    fun topicChanges() = TestServer.test(settings = {}) {
        val wire = engine.internalSerialization.kotlinWireFormat
        val changes = CollectionChanges(listOf(EntryChange(old = Sample("1", "A"), new = null), EntryChange(old = null, new = Sample("2", "B", "n"))))
        val serializer = CollectionChanges.serializer(Sample.serializer())
        assertEquals(changes, wire.decodeFromByteArray(serializer, wire.encodeToByteArray(serializer, changes)))
    }
}
