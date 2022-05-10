package com.lightningkite.ktordb

import com.github.jershell.kbson.BsonEncoder
import com.github.jershell.kbson.FlexibleDecoder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.bson.BsonType
import org.bson.UuidRepresentation
import org.litote.kmongo.serialization.registerSerializer
import java.util.*


fun fixUuidSerialization() {
    registerSerializer(ServerFileSerialization)
    registerSerializer(object: KSerializer<UUID> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UUIDSerializer", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: UUID) {
            encoder as BsonEncoder
            encoder.encodeUUID(value, UuidRepresentation.STANDARD)
        }

        override fun deserialize(decoder: Decoder): UUID {
            return when (decoder) {
                is FlexibleDecoder -> {
                    when (decoder.reader.currentBsonType) {
                        BsonType.STRING -> {
                            UUID.fromString(decoder.decodeString())
                        }
                        BsonType.BINARY -> {
                            decoder.reader.readBinaryData().asUuid(UuidRepresentation.STANDARD)
                        }
                        else -> throw SerializationException("Unsupported ${decoder.reader.currentBsonType} reading object id")
                    }
                }
                else -> throw SerializationException("Unknown decoder type")
            }
        }
    })
}