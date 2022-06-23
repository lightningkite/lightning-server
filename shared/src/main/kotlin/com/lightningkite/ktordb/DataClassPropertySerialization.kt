package com.lightningkite.ktordb

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.internal.GeneratedSerializer


private val KSerializer_fields = HashMap<SerialDescriptor, Map<String, PartialDataClassProperty<*>>>()
@OptIn(InternalSerializationApi::class)
@Suppress("UNCHECKED_CAST")
var <K> KSerializer<K>.fields: Map<String, PartialDataClassProperty<K>>
    get() = KSerializer_fields[this.descriptor] as? Map<String, PartialDataClassProperty<K>> ?: throw IllegalStateException("Fields not assigned yet for $this; serial descriptor ${descriptor}")
    set(value) { KSerializer_fields[this.descriptor] = value }

class PartialDataClassPropertySerializer<T>(val inner: KSerializer<T>): KSerializer<PartialDataClassProperty<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PartialDataClassProperty<${inner.descriptor.serialName}>", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): PartialDataClassProperty<T> {
        val value = decoder.decodeString()
        val name = value
        return inner.fields[name]!!
    }

    override fun serialize(encoder: Encoder, value: PartialDataClassProperty<T>) {
        encoder.encodeString(value.name)
    }
}