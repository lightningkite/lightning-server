package com.lightningkite.lightningdb

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.nullable
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
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.util.UUID", PrimitiveKind.STRING)
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

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
}

object ZonedDateTimeSerializer: KSerializer<ZonedDateTime> {
    override fun deserialize(decoder: Decoder): ZonedDateTime = ZonedDateTime.parse(decoder.decodeString())
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.ZonedDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ZonedDateTime) = encoder.encodeString(value.toString())
}

object LocalDateSerializer: KSerializer<LocalDate> {
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString().trim('z', 'Z'))
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
}

object LocalTimeSerializer: KSerializer<LocalTime> {
    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString().trim('z', 'Z'))
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.LocalTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.toString())
}

object OffsetDateTimeSerializer: KSerializer<OffsetDateTime> {
    override fun deserialize(decoder: Decoder): OffsetDateTime = OffsetDateTime.parse(decoder.decodeString())
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.OffsetDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: OffsetDateTime) = encoder.encodeString(value.toString())
}

object DurationSerializer: KSerializer<Duration> {
    override fun deserialize(decoder: Decoder): Duration {
        val raw = decoder.decodeString()
        return raw.toLongOrNull()?.let { Duration.ofMillis(it) } ?: Duration.parse(raw)
    }
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Duration", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeString(value.toString())
}

object DurationMsSerializer: KSerializer<Duration> {
    override fun deserialize(decoder: Decoder): Duration {
        return Duration.ofMillis(decoder.decodeLong())
    }
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Duration", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeLong(value.toMillis())
}

val ClientModule = SerializersModule {
    contextual(UUID::class, UUIDSerializer)
    contextual(Instant::class, InstantSerializer)
    contextual(ZonedDateTime::class, ZonedDateTimeSerializer)
    contextual(LocalDate::class, LocalDateSerializer)
    contextual(LocalTime::class, LocalTimeSerializer)
    contextual(OffsetDateTime::class, OffsetDateTimeSerializer)
    contextual(ServerFile::class, ServerFileSerialization)
    contextual(Duration::class, DurationSerializer)
    contextual(Optional::class) { list ->
        @Suppress("UNCHECKED_CAST")
        OptionalSerializer(list[0] as KSerializer<Any>)
    }
}
@Suppress("OPT_IN_USAGE")
class OptionalSerializer<T: Any>(val inner: KSerializer<T>): KSerializer<Optional<T>> {
    val nullable = inner.nullable
    override val descriptor: SerialDescriptor
        get() = SerialDescriptor("Optional<${inner.descriptor.serialName}>", nullable.descriptor)
    override fun deserialize(decoder: Decoder): Optional<T> = Optional.ofNullable(nullable.deserialize(decoder))
    override fun serialize(encoder: Encoder, value: Optional<T>) {
        nullable.serialize(encoder, if(value.isPresent) value.get() else null)
    }
}