package com.lightningkite.lightningdb

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
import java.util.*
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds


object UUIDSerializer : KSerializer<UUID> {
    override fun deserialize(decoder: Decoder): UUID = try {
        UUID.fromString(decoder.decodeString())
    } catch (e: java.lang.IllegalArgumentException) {
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