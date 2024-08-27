package com.lightningkite.serialization

import com.lightningkite.*
import kotlinx.datetime.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Duration.Companion.seconds


interface KSerializerWithDefault<T> : KSerializer<T> {
    val default: T
}

@OptIn(ExperimentalSerializationApi::class)
object DefaultDecoder : Decoder {
    val defaults = HashMap<String, Any?>()

    init {
        defaults[UUIDSerializer.descriptor.serialName] = uuid("00000000-0000-0000-0000-000000000000")
        defaults[DurationSerializer.descriptor.serialName] = 0.seconds
        defaults[InstantIso8601Serializer.descriptor.serialName] = Instant.fromEpochMilliseconds(0)
        defaults[LocalTimeIso8601Serializer.descriptor.serialName] = LocalTime(0, 0, 0)
        defaults[LocalDateIso8601Serializer.descriptor.serialName] = LocalDate(1970, 1, 1)
        defaults[LocalDateTimeIso8601Serializer.descriptor.serialName] =
            LocalDateTime(LocalDate(1970, 1, 1), LocalTime(0, 0, 0))
        defaults[ZonedDateTimeIso8601Serializer.descriptor.serialName] =
            ZonedDateTime(LocalDateTime(LocalDate(1970, 1, 1), LocalTime(0, 0, 0)), TimeZone.UTC)
        defaults[OffsetDateTimeIso8601Serializer.descriptor.serialName] =
            OffsetDateTime(LocalDateTime(LocalDate(1970, 1, 1), LocalTime(0, 0, 0)), UtcOffset.ZERO)
    }

    override var serializersModule: SerializersModule = ClientModule
    override fun decodeBoolean() = false
    override fun decodeByte() = 0.toByte()
    override fun decodeChar() = ' '
    override fun decodeDouble() = 0.0
    override fun decodeFloat() = 0f
    override fun decodeInt() = 0
    override fun decodeLong() = 0L
    override fun decodeShort() = 0.toShort()
    override fun decodeString() = ""
    override fun decodeNotNullMark(): Boolean = true
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder = object : CompositeDecoder {
        override val serializersModule: SerializersModule get() = this@DefaultDecoder.serializersModule

        override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int) = false
        override fun decodeByteElement(descriptor: SerialDescriptor, index: Int) = 0.toByte()
        override fun decodeCharElement(descriptor: SerialDescriptor, index: Int) = ' '
        override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int) = 0.0
        override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int) = 0f
        override fun decodeIntElement(descriptor: SerialDescriptor, index: Int) = 0
        override fun decodeLongElement(descriptor: SerialDescriptor, index: Int) = 0L
        override fun decodeShortElement(descriptor: SerialDescriptor, index: Int) = 0.toShort()
        override fun decodeStringElement(descriptor: SerialDescriptor, index: Int) = ""

        var index = -1
        override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
            while (true) {
                index++
                if (index >= descriptor.elementsCount) return CompositeDecoder.DECODE_DONE
                if (!descriptor.isElementOptional(index)) break
            }
            return index
        }

        override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder = this@DefaultDecoder

        @ExperimentalSerializationApi
        override fun <T : Any> decodeNullableSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T?>,
            previousValue: T?
        ): T? = null

        @Suppress("UNCHECKED_CAST")
        override fun <T> decodeSerializableElement(
            descriptor: SerialDescriptor,
            index: Int,
            deserializer: DeserializationStrategy<T>,
            previousValue: T?
        ): T {
            (deserializer as? KSerializerWithDefault<*>)?.default?.let { return it as T }
            (defaults[deserializer.descriptor.serialName])?.let { return it as T }
            return deserializer.deserialize(this@DefaultDecoder)
        }

        override fun endStructure(descriptor: SerialDescriptor) {}
    }

    override fun <T : Any> decodeNullableSerializableValue(deserializer: DeserializationStrategy<T?>): T? = null
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        return (defaults[deserializer.descriptor.serialName] as? T) ?: deserializer.deserialize(this)
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int = 0
    override fun decodeInline(descriptor: SerialDescriptor): Decoder = this
    override fun decodeNull(): Nothing? = null
}

fun <T> KSerializer<T>.default(): T {
    if (this is KSerializerWithDefault<T>) return this.default
    return DefaultDecoder.decodeSerializableValue(this)
}