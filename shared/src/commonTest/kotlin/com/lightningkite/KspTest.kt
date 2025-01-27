package com.lightningkite

import com.lightningkite.lightningdb.GenerateDataClassPaths
import com.lightningkite.serialization.innerElement
import com.lightningkite.serialization.serializableProperties
import com.lightningkite.serialization.tryChildSerializers
import kotlinx.datetime.Instant
import kotlinx.serialization.Contextual
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
class KspTest {
    @Test fun testSerializable() {
        prepareModelsSharedTest()
        val serializers = KspTestType.serializer().tryChildSerializers()!!
        println(serializers.joinToString("\n"))
        val perPropSerializers = KspTestType.serializer().serializableProperties!!.map { it.serializer }
        println(perPropSerializers.joinToString("\n"))
        assertEquals(serializers.map { it.descriptor.serialName }, perPropSerializers.map { it.descriptor.serialName })
        assertEquals(serializers.map { try {
            it.innerElement()
        } catch(e: Exception) { null } ?.descriptor?.serialName }, perPropSerializers.map { try {
            it.innerElement()
        } catch(e: Exception) { null } ?.descriptor?.serialName })
    }
}

@GenerateDataClassPaths
@Serializable
data class KspTestType(
    @Contextual val a: Instant = Instant.fromEpochMilliseconds(0),
    val b: @Contextual Instant = Instant.fromEpochMilliseconds(0),
    @Serializable(WeirdSerializer::class) val c: Int = 0,
    val d: @Serializable(WeirdSerializer::class) Int = 0,
    val e: @Serializable(NullAsMinIntSerializer::class) (Int?) = null,
    val f: List<@Serializable(NullAsMinIntSerializer::class) (Int?)> = listOf(null),
)

object WeirdSerializer: KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("kotlin.Int/weird", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(1 + value)
    }

    override fun deserialize(decoder: Decoder): Int {
        return decoder.decodeInt() - 1
    }

}
object NullAsMinIntSerializer: KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("kotlin.Int/nullAsMin", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int?) {
        encoder.encodeInt(value ?: Int.MIN_VALUE)
    }

    override fun deserialize(decoder: Decoder): Int? {
        return decoder.decodeInt().takeUnless { it == Int.MIN_VALUE }
    }

}