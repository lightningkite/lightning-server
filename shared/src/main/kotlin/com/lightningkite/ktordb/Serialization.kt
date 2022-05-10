package com.lightningkite.ktordb

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import java.time.*
import java.time.format.DateTimeParseException
import java.util.*


object UUIDSerializer: KSerializer<UUID> {
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
}

object InstantSerializer: KSerializer<Instant> {
    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            Instant.parse(text + "z")
        }
    }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
}

object ZonedDateTimeSerializer: KSerializer<ZonedDateTime> {
    override fun deserialize(decoder: Decoder): ZonedDateTime = ZonedDateTime.parse(decoder.decodeString())
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("ZonedDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ZonedDateTime) = encoder.encodeString(value.toString())
}

object LocalDateSerializer: KSerializer<LocalDate> {
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString().trim('z', 'Z'))
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
}

object LocalTimeSerializer: KSerializer<LocalTime> {
    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString().trim('z', 'Z'))
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.toString())
}

object OffsetDateTimeSerializer: KSerializer<OffsetDateTime> {
    override fun deserialize(decoder: Decoder): OffsetDateTime = OffsetDateTime.parse(decoder.decodeString())
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("OffsetDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: OffsetDateTime) = encoder.encodeString(value.toString())
}

val ClientModule = SerializersModule {
    contextual(UUID::class, UUIDSerializer)
    contextual(Instant::class, InstantSerializer)
    contextual(ZonedDateTime::class, ZonedDateTimeSerializer)
    contextual(LocalDate::class, LocalDateSerializer)
    contextual(LocalTime::class, LocalTimeSerializer)
    contextual(OffsetDateTime::class, OffsetDateTimeSerializer)
    contextual(ServerFile::class, ServerFileSerialization)
}