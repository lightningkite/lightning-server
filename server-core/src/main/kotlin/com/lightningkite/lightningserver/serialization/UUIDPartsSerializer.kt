package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.datetime.Instant
import java.util.*
import com.lightningkite.UUID

object UUIDPartsSerializer: KSerializer<UUID> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.lightningkite.UUID/parts") {
        element("mostSignificantBits", Long.serializer().descriptor)
        element("leastSignificantBits", Long.serializer().descriptor)
    }

    override fun deserialize(decoder: Decoder): UUID {
        val s = decoder.beginStructure(descriptor)
        var m = 0L
        var l = 0L
        while(true) {
            var index = s.decodeElementIndex(descriptor)
            when(index) {
                0 -> m = s.decodeLongElement(descriptor, 0)
                1 -> l = s.decodeLongElement(descriptor, 1)
                CompositeDecoder.DECODE_DONE -> break
                else -> {}
            }
        }
        s.endStructure(descriptor)
        return UUID.fromLongs(m, l)
    }

    override fun serialize(encoder: Encoder, value: UUID) {
        val s = encoder.beginStructure(descriptor)
        value.toLongs { m, l ->
            s.encodeLongElement(descriptor, 0, m)
            s.encodeLongElement(descriptor, 1, l)
        }
        s.endStructure(descriptor)
    }
}

object InstantLongSerializer: KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("kotlinx.datetime.Instant/epochMilliseconds", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Instant {
        return Instant.fromEpochMilliseconds(decoder.decodeLong())
    }

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeLong(value.toEpochMilliseconds())
    }
}
