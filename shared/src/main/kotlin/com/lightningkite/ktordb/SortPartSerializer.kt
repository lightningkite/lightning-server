@file:OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)

package com.lightningkite.ktordb

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class SortPartSerializer<T>(val inner: KSerializer<T>): KSerializer<SortPart<T>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("SortPart<${inner.descriptor.serialName}>", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): SortPart<T> {
        val value = decoder.decodeString()
        val descending = value.startsWith('-')
        val name = value.removePrefix("-")
        val prop = inner.fields[name]
        @Suppress("UNCHECKED_CAST")
        val typedProp = prop as PartialDataClassProperty<T>
        return SortPart(typedProp, !descending)
    }

    override fun serialize(encoder: Encoder, value: SortPart<T>) {
        if(value.ascending)
            encoder.encodeString(value.field.name)
        else
            encoder.encodeString("-" + value.field.name)
    }
}