package com.lightningkite

import kotlinx.datetime.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.max
import kotlin.time.Duration

@Serializable(ZonedDateTimeIso8601Serializer::class)
data class ZonedDateTime(val dateTime: LocalDateTime, val zone: TimeZone) {
    override fun toString(): String = "$dateTime${zone.offsetAt(dateTime.toInstant(zone))}[${zone.id}]"
    companion object {
        fun parse(string: String): ZonedDateTime {
            var dateTimeFinishedIndex = string.indexOfAny(charArrayOf('Z', '[', '+', '-'), 14)
            if(dateTimeFinishedIndex == -1) dateTimeFinishedIndex = string.length
            return ZonedDateTime(
                LocalDateTime.parse(string.substring(0, dateTimeFinishedIndex)),
                if(dateTimeFinishedIndex == string.length) TimeZone.UTC
                else if(string.contains('[')) TimeZone.of(string.substringAfterLast('[').substringBefore(']'))
                else if(string[dateTimeFinishedIndex] == 'Z') TimeZone.UTC
                else FixedOffsetTimeZone(UtcOffset.parse(string.substring(dateTimeFinishedIndex, string.length)))
            )
        }
    }
    val date: LocalDate get() = dateTime.date
    val time: LocalTime get() = dateTime.time

    operator fun plus(duration: Duration): ZonedDateTime = toInstant().plus(duration).atZone(zone)
    operator fun minus(duration: Duration): ZonedDateTime = toInstant().minus(duration).atZone(zone)
    fun toInstant(): Instant = dateTime.toInstant(zone)
    fun toOffsetDateTime(): OffsetDateTime = OffsetDateTime(dateTime, zone.offsetAt(dateTime.toInstant(zone)))
}

object ZonedDateTimeIso8601Serializer : KSerializer<ZonedDateTime> {
    override fun deserialize(decoder: Decoder): ZonedDateTime =
        try {
            ZonedDateTime.parse(decoder.decodeString())
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message)
        }
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.ZonedDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ZonedDateTime) = encoder.encodeString(value.toString())
}

@Suppress("NOTHING_TO_INLINE")
inline fun LocalDateTime.atZone(zone: TimeZone) = ZonedDateTime(this, zone)
@Suppress("NOTHING_TO_INLINE")
inline fun Instant.atZone(zone: TimeZone) = ZonedDateTime(this.toLocalDateTime(zone), zone)

fun nowLocal() = ZonedDateTime(now().toLocalDateTime(TimeZone.currentSystemDefault()), TimeZone.currentSystemDefault())

@Serializable(OffsetDateTimeIso8601Serializer::class)
data class OffsetDateTime(val dateTime: LocalDateTime, val offset: UtcOffset) {
    override fun toString(): String = "$dateTime$offset"
    companion object {
        fun parse(string: String): OffsetDateTime {
            var dateTimeFinishedIndex = string.indexOfAny(charArrayOf('Z', '[', '+', '-'), 14)
            if(dateTimeFinishedIndex == -1) dateTimeFinishedIndex = string.length
            return OffsetDateTime(
                LocalDateTime.parse(string.substring(0, dateTimeFinishedIndex)),
                if(dateTimeFinishedIndex == string.length) UtcOffset.ZERO
                else if(string.contains('[')) TimeZone.of(string.substringAfterLast('[').substringBefore(']')).offsetAt(now())
                else if(string[dateTimeFinishedIndex] == 'Z') UtcOffset.ZERO
                else UtcOffset.parse(string.substring(dateTimeFinishedIndex, string.length))
            )
        }
    }
    val date: LocalDate get() = dateTime.date
    val time: LocalTime get() = dateTime.time

    operator fun plus(duration: Duration): OffsetDateTime = toInstant().plus(duration).atOffset(offset)
    operator fun minus(duration: Duration): OffsetDateTime = toInstant().minus(duration).atOffset(offset)
    fun toInstant(): Instant = dateTime.toInstant(offset)
}

object OffsetDateTimeIso8601Serializer : KSerializer<OffsetDateTime> {
    override fun deserialize(decoder: Decoder): OffsetDateTime =
        try {
            OffsetDateTime.parse(decoder.decodeString())
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message)
        }
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.OffsetDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: OffsetDateTime) = encoder.encodeString(value.toString())
}
@Suppress("NOTHING_TO_INLINE")
inline fun LocalDateTime.atOffset(offset: UtcOffset) = OffsetDateTime(this, offset)
@Suppress("NOTHING_TO_INLINE")
inline fun Instant.atOffset(offset: UtcOffset) = OffsetDateTime(this.toLocalDateTime(FixedOffsetTimeZone(offset)), offset)
