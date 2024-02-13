package com.lightningkite.lightningdb

import com.lightningkite.*
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.serializers.InstantIso8601Serializer
import kotlinx.datetime.serializers.LocalDateIso8601Serializer
import kotlinx.datetime.serializers.LocalDateTimeIso8601Serializer
import kotlinx.datetime.serializers.LocalTimeIso8601Serializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds


object UUIDSerializer : KSerializer<UUID> {
    override fun deserialize(decoder: Decoder): UUID = try {
        uuid(decoder.decodeString())
    } catch (e: IllegalArgumentException) {
        throw SerializationException(e.message)
    }

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.lightningkite.UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
}

object DurationMsSerializer : KSerializer<Duration> {
    override fun deserialize(decoder: Decoder): Duration =
        try {
            decoder.decodeLong().milliseconds
        } catch (e: Exception) {
            throw SerializationException(e.message)
        }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("kotlin.time.Duration", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeLong(value.inWholeMilliseconds)
}

object DurationSerializer : KSerializer<Duration> {
    override fun deserialize(decoder: Decoder): Duration =
        try {
            Duration.parse(decoder.decodeString())
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message)
        }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("kotlin.time.Duration", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeString(value.toString())
}

object InstantIso8601SerializerWrapper : KSerializer<Instant> {

    override val descriptor: SerialDescriptor = InstantIso8601Serializer.descriptor

    override fun deserialize(decoder: Decoder): Instant =
        try {
            InstantIso8601Serializer.deserialize(decoder)
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message)
        }

    override fun serialize(encoder: Encoder, value: Instant) = InstantIso8601Serializer.serialize(encoder, value)

}

object LocalDateIso8601SerializerWrapper : KSerializer<LocalDate> {

    override val descriptor: SerialDescriptor = LocalDateIso8601Serializer.descriptor

    override fun deserialize(decoder: Decoder): LocalDate =
        try {
            LocalDateIso8601Serializer.deserialize(decoder)
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message)
        }

    override fun serialize(encoder: Encoder, value: LocalDate) = LocalDateIso8601Serializer.serialize(encoder, value)

}

object LocalTimeIso8601SerializerWrapper : KSerializer<LocalTime> {

    override val descriptor: SerialDescriptor = LocalTimeIso8601Serializer.descriptor

    override fun deserialize(decoder: Decoder): LocalTime =
        try {
            LocalTimeIso8601Serializer.deserialize(decoder)
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message)
        }

    override fun serialize(encoder: Encoder, value: LocalTime) = LocalTimeIso8601Serializer.serialize(encoder, value)

}

object LocalDateTimeIso8601SerializerWrapper : KSerializer<LocalDateTime> {

    override val descriptor: SerialDescriptor = LocalDateTimeIso8601Serializer.descriptor

    override fun deserialize(decoder: Decoder): LocalDateTime =
        try {
            LocalDateTimeIso8601Serializer.deserialize(decoder)
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message)
        }

    override fun serialize(encoder: Encoder, value: LocalDateTime) =
        LocalDateTimeIso8601Serializer.serialize(encoder, value)

}


val ClientModule = SerializersModule {
    contextual(UUID::class, UUIDSerializer)
    contextual(Instant::class, InstantIso8601SerializerWrapper)
    contextual(LocalDate::class, LocalDateIso8601SerializerWrapper)
    contextual(LocalTime::class, LocalTimeIso8601SerializerWrapper)
    contextual(LocalDateTime::class, LocalDateTimeIso8601SerializerWrapper)
    contextual(OffsetDateTime::class, OffsetDateTimeIso8601Serializer)
    contextual(ZonedDateTime::class, ZonedDateTimeIso8601Serializer)
    contextual(Duration::class, DurationSerializer)
    contextual(ServerFile::class, ServerFileSerialization)
}
