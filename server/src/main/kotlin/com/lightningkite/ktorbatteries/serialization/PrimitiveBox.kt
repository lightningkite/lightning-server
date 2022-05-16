package com.lightningkite.ktorbatteries.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@kotlinx.serialization.Serializable(PrimitiveBoxSerializer::class)
data class PrimitiveBox<T>(val value: T)

class PrimitiveBoxSerializer<T>(val sub: KSerializer<T>): KSerializer<PrimitiveBox<T>> {
    override val descriptor: SerialDescriptor get() = sub.descriptor
    override fun deserialize(decoder: Decoder): PrimitiveBox<T> = PrimitiveBox(sub.deserialize(decoder))
    override fun serialize(encoder: Encoder, value: PrimitiveBox<T>) { sub.serialize(encoder, value.value) }
}
