package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.util.*

object UUIDPartsSerializer: KSerializer<UUID> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("UUID") {
        element("mostSignificantBits", Long.serializer().descriptor)
        element("leastSignificantBits", Long.serializer().descriptor)
    }

    override fun deserialize(decoder: Decoder): UUID {
        val s = decoder.beginStructure(descriptor)
        val m = s.decodeLongElement(descriptor, 0)
        val l = s.decodeLongElement(descriptor, 1)
        s.endStructure(descriptor)
        return UUID(m, l)
    }

    override fun serialize(encoder: Encoder, value: UUID) {
        val s = encoder.beginStructure(descriptor)
        s.encodeLongElement(descriptor, 0, value.mostSignificantBits)
        s.encodeLongElement(descriptor, 1, value.leastSignificantBits)
        s.endStructure(descriptor)
    }
}

object InstantLongSerializer: KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Instant {
        return Instant.ofEpochMilli(decoder.decodeLong())
    }

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeLong(value.toEpochMilli())
    }
}
