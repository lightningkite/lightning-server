package com.lightningkite.lightningdb

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*


object UUIDSerializer : KSerializer<UUID> {
    override fun deserialize(decoder: Decoder): UUID = try {
        UUID.fromString(decoder.decodeString())
    } catch (e: java.lang.IllegalArgumentException) {
        throw SerializationException(e.message)
    }
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.util.UUID", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
}

object InstantSerializer : KSerializer<Instant> {
    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        try {
            return try {
                Instant.parse(text)
            } catch (e: DateTimeParseException) {
                Instant.parse(text + "z")
            }
        } catch (e: DateTimeParseException) {
            throw SerializationException(e.message)
        }
    }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
}

object ZonedDateTimeSerializer : KSerializer<ZonedDateTime> {
    override fun deserialize(decoder: Decoder): ZonedDateTime = try {
        ZonedDateTime.parse(decoder.decodeString(), DateTimeFormatter.ISO_ZONED_DATE_TIME)
    } catch (e: DateTimeParseException) {
        throw SerializationException(e.message)
    }

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.ZonedDateTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ZonedDateTime) = encoder.encodeString(DateTimeFormatter.ISO_ZONED_DATE_TIME.format(value))
}

object LocalDateSerializer : KSerializer<LocalDate> {
    override fun deserialize(decoder: Decoder): LocalDate = try {
        LocalDate.parse(decoder.decodeString().trim('z', 'Z'))
    } catch (e: DateTimeParseException) {
        throw SerializationException(e.message)
    }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
}

object LocalTimeSerializer : KSerializer<LocalTime> {
    override fun deserialize(decoder: Decoder): LocalTime = try {
        LocalTime.parse(decoder.decodeString().trim('z', 'Z'))
    } catch (e: DateTimeParseException) {
        throw SerializationException(e.message)
    }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.LocalTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.toString())
}

object OffsetDateTimeSerializer : KSerializer<OffsetDateTime> {
    override fun deserialize(decoder: Decoder): OffsetDateTime = try{ OffsetDateTime.parse(decoder.decodeString()) } catch (e:DateTimeParseException){ throw SerializationException(e.message)}
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.OffsetDateTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: OffsetDateTime) = encoder.encodeString(value.toString())
}

object DurationSerializer : KSerializer<Duration> {
    override fun deserialize(decoder: Decoder): Duration {
        val raw = decoder.decodeString()
        return raw.toLongOrNull()?.let { Duration.ofMillis(it) } ?: try{ Duration.parse(raw) } catch (e:DateTimeParseException){ throw SerializationException(e.message)}
    }

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Duration", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeString(value.toString())
}

object DurationMsSerializer : KSerializer<Duration> {
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
class OptionalSerializer<T : Any>(val inner: KSerializer<T>) : KSerializer<Optional<T>> {
    val nullable = inner.nullable
    override val descriptor: SerialDescriptor
        get() = if(inner.descriptor.kind is PrimitiveKind) PrimitiveSerialDescriptor("Optional<${inner.descriptor.serialName}>", nullable.descriptor.kind as PrimitiveKind) else SerialDescriptor("Optional<${inner.descriptor.serialName}>", nullable.descriptor)

    override fun deserialize(decoder: Decoder): Optional<T> = Optional.ofNullable(nullable.deserialize(decoder))
    override fun serialize(encoder: Encoder, value: Optional<T>) {
        nullable.serialize(encoder, if (value.isPresent) value.get() else null)
    }
}