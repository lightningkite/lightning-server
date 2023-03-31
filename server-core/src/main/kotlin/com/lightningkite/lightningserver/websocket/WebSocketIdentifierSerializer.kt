package com.lightningkite.lightningserver.websocket

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object WebSocketIdentifierSerializer : KSerializer<WebSocketIdentifier> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("WebSocketIdentifier", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): WebSocketIdentifier = WebSocketIdentifier(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: WebSocketIdentifier) {
        encoder.encodeString(value.string)
    }
}