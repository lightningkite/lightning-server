package com.lightningkite.lightningserver.serialization

import com.lightningkite.lightningserver.metrics.roundTo
import com.lightningkite.uuid
import kotlinx.datetime.Clock
import com.lightningkite.now
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.junit.Assert.*
import org.junit.Test
import kotlinx.datetime.Instant
import java.util.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import com.lightningkite.UUID
import com.lightningkite.uuid

@Serializable
data class BsonSerTest(
    val x: Int = 42,
    @Contextual val y: Instant = now().roundTo(1.milliseconds),
    @Contextual val z: UUID = uuid()
)

class SerializationTest {
    @Test fun bson() {
        val v = BsonSerTest()
        println(Serialization.bson.stringify(BsonSerTest.serializer(), v).toJson())
        assertEquals(v, Serialization.bson.load(BsonSerTest.serializer(), Serialization.bson.dump(BsonSerTest.serializer(), v)))
    }
}