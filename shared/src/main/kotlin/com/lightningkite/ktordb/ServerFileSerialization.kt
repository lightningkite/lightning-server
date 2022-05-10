package com.lightningkite.ktordb

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object ServerFileSerialization : KSerializer<ServerFile> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ServerFile", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ServerFile) {
        encoder.encodeString(value.location)
    }

    override fun deserialize(decoder: Decoder): ServerFile {
        return ServerFile(decoder.decodeString())
    }
}