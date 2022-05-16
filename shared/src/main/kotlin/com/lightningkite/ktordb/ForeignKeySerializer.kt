package com.lightningkite.ktordb

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

class ForeignKeySerializer<Model: HasId<ID>, ID: Comparable<ID>>(
    val modelSerializer: KSerializer<Model>,
    val idSerializer: KSerializer<ID>
): KSerializer<ForeignKey<Model, ID>> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = when(idSerializer.descriptor.kind) {
        is PrimitiveKind -> PrimitiveSerialDescriptor("ForeignKey<${modelSerializer.descriptor.serialName}>", idSerializer.descriptor.kind as PrimitiveKind)
        else -> SerialDescriptor("ForeignKey<${modelSerializer.descriptor.serialName}>", idSerializer.descriptor)
    }
    override fun deserialize(decoder: Decoder): ForeignKey<Model, ID> = ForeignKey(idSerializer.deserialize(decoder))
    override fun serialize(encoder: Encoder, value: ForeignKey<Model, ID>) { idSerializer.serialize(encoder, value.id) }
}