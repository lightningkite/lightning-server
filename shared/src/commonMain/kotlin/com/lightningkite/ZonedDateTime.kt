package com.lightningkite

import kotlinx.datetime.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
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
            var zoneNameIndex = string.lastIndexOf('[')
            if(zoneNameIndex == -1) {
                val offsetStartIndex = max(string.lastIndexOf('+'), string.lastIndexOf('-'))
                val offset = UtcOffset.parse(string.substring(offsetStartIndex, string.length))
                return ZonedDateTime(
                    dateTime = LocalDateTime.parse(string.substring(0, offsetStartIndex)),
                    zone = FixedOffsetTimeZone(offset)
                )
            } else {
                val offsetStartIndex = max(string.lastIndexOf('+', zoneNameIndex), string.lastIndexOf('-', zoneNameIndex))
                return ZonedDateTime(
                    dateTime = LocalDateTime.parse(string.substring(0, offsetStartIndex)),
                    zone = TimeZone.of(string.substring(zoneNameIndex + 1, string.length - 1))
                )
            }
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
    override fun deserialize(decoder: Decoder): ZonedDateTime = ZonedDateTime.parse(decoder.decodeString())
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.ZonedDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ZonedDateTime) = encoder.encodeString(value.toString())
}

inline fun LocalDateTime.atZone(zone: TimeZone) = ZonedDateTime(this, zone)
inline fun Instant.atZone(zone: TimeZone) = ZonedDateTime(this.toLocalDateTime(zone), zone)

fun nowLocal() = ZonedDateTime(now().toLocalDateTime(TimeZone.currentSystemDefault()), TimeZone.currentSystemDefault())

@Serializable(OffsetDateTimeIso8601Serializer::class)
data class OffsetDateTime(val dateTime: LocalDateTime, val offset: UtcOffset) {
    override fun toString(): String = "$dateTime$offset"
    companion object {
        fun parse(string: String): OffsetDateTime {
            var zoneNameIndex = string.lastIndexOf('[')
            if(zoneNameIndex == -1) zoneNameIndex = string.length
            val offsetStartIndex = max(string.lastIndexOf('+', zoneNameIndex), string.lastIndexOf('-', zoneNameIndex))
            val offset = string.substring(offsetStartIndex, zoneNameIndex)
            return OffsetDateTime(
                dateTime = LocalDateTime.parse(string.substring(0, offsetStartIndex)),
                offset = UtcOffset.parse(offset)
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
    override fun deserialize(decoder: Decoder): OffsetDateTime = OffsetDateTime.parse(decoder.decodeString())
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.OffsetDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: OffsetDateTime) = encoder.encodeString(value.toString())
}
inline fun LocalDateTime.atOffset(offset: UtcOffset) = OffsetDateTime(this, offset)
inline fun Instant.atOffset(offset: UtcOffset) = OffsetDateTime(this.toLocalDateTime(FixedOffsetTimeZone(offset)), offset)
