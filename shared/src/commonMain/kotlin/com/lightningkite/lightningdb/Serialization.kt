package com.lightningkite.lightningdb

import com.lightningkite.UUID
import com.lightningkite.uuid
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializerOrNull
import kotlinx.datetime.*
import kotlinx.datetime.serializers.*

import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds


object UUIDSerializer : KSerializer<UUID> {
    override fun deserialize(decoder: Decoder): UUID = try {
        uuid(decoder.decodeString())
    } catch (e: IllegalArgumentException) {
        throw SerializationException(e.message)
    }
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.util.UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
}

object DurationMsSerializer : KSerializer<Duration> {
    override fun deserialize(decoder: Decoder): Duration {
        return decoder.decodeLong().milliseconds
    }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("kotlin.time.Duration", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeLong(value.inWholeMilliseconds)
}

object DurationSerializer : KSerializer<Duration> {
    override fun deserialize(decoder: Decoder): Duration = Duration.parse(decoder.decodeString())
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("kotlin.time.Duration", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeString(value.toString())
}

val ClientModule = SerializersModule {
    contextual(UUID::class, UUIDSerializer)

    contextual(Instant::class, InstantIso8601Serializer)
    contextual(LocalDate::class, LocalDateIso8601Serializer)
    contextual(LocalTime::class, LocalTimeIso8601Serializer)
    contextual(LocalDateTime::class, LocalDateTimeIso8601Serializer)
    contextual(Duration::class, DurationSerializer)
    contextual(ServerFile::class, ServerFileSerialization)
}
