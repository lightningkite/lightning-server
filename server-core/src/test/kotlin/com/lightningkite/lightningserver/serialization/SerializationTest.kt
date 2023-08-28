package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalUnit
import java.util.*

@Serializable
data class BsonSerTest(
    val x: Int = 42,
    @Contextual val y: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS),
    @Contextual val z: UUID = UUID.randomUUID()
)

class SerializationTest {
    @Test fun bson() {
        val v = BsonSerTest()
        println(Serialization.bson.stringify(BsonSerTest.serializer(), v).toJson())
        assertEquals(v, Serialization.bson.load(BsonSerTest.serializer(), Serialization.bson.dump(BsonSerTest.serializer(), v)))
    }
}