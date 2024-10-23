package com.lightningkite.lightningserver.serialization

import com.github.jershell.kbson.*
import com.lightningkite.serialization.DurationMsSerializer
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.*
import org.bson.BsonType
import java.math.BigDecimal
import kotlinx.datetime.*
import kotlinx.datetime.TimeZone
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.reflect.KProperty
import kotlin.time.Duration
import com.lightningkite.UUID
import com.lightningkite.toJavaUuid
import com.lightningkite.toKotlinUuid
import org.bson.UuidRepresentation
import org.bson.types.ObjectId

val BsonOverrides = SerializersModule {
    contextual(Duration::class, DurationMsSerializer)
    contextual(UUID::class, UUIDSerializer)
    contextual(ObjectId::class, ObjectIdSerializer)
    contextual(BigDecimal::class, BigDecimalSerializer)
    contextual(ByteArray::class, ByteArraySerializer)
    contextual(Instant::class, MongoInstantSerializer)
//    contextual(ZonedDateTime::class, MongoZonedDateTimeSerializer)
//    contextual(OffsetDateTime::class, MongoOffsetDateTimeSerializer)
//    contextual(OffsetTime::class, MongoOffsetTimeSerializer)
    contextual(LocalDate::class, MongoLocalDateSerializer)
    contextual(LocalDateTime::class, MongoLocalDateTimeSerializer)
    contextual(LocalTime::class, MongoLocalTimeSerializer)
//    contextual(BsonTimestamp::class, BsonTimestampSerializer)
    contextual(Locale::class, MongoLocaleSerializer)
//    contextual(Binary::class, BinarySerializer)
}

/**
 *
 */
abstract class TemporalExtendedJsonSerializer<T> : KSerializer<T> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(javaClass.simpleName, PrimitiveKind.STRING)

    /**
     * Returns the number of milliseconds since January 1, 1970, 00:00:00 GMT
     * represented by this <tt>Temporal</tt> object.
     *
     * @return  the number of milliseconds since January 1, 1970, 00:00:00 GMT
     *          represented by this date.
     */
    abstract fun epochMillis(temporal: T): Long

    abstract fun instantiate(date: Long): T

    override fun serialize(encoder: Encoder, value: T) {
        encoder as BsonEncoder
        encoder.encodeDateTime(epochMillis(value))
    }

    override fun deserialize(decoder: Decoder): T {
        return when (decoder) {
            is FlexibleDecoder -> {
                instantiate(
                    when (decoder.reader.currentBsonType) {
                        BsonType.STRING -> decoder.decodeString().toLong()
                        BsonType.DATE_TIME -> decoder.reader.readDateTime()
                        BsonType.INT32 -> decoder.decodeInt().toLong()
                        BsonType.INT64 -> decoder.decodeLong()
                        BsonType.DOUBLE -> decoder.decodeDouble().toLong()
                        BsonType.DECIMAL128 -> decoder.reader.readDecimal128().toLong()
                        BsonType.TIMESTAMP -> TimeUnit.SECONDS.toMillis(decoder.reader.readTimestamp().time.toLong())
                        else -> throw SerializationException("Unsupported ${decoder.reader.currentBsonType} reading date")
                    }
                )
            }
            else -> throw SerializationException("Unknown decoder type")
        }
    }
}

//@Serializer(forClass = Instant::class)
object MongoInstantSerializer : TemporalExtendedJsonSerializer<Instant>() {

    override fun epochMillis(temporal: Instant): Long = temporal.toEpochMilliseconds()

    override fun instantiate(date: Long): Instant = Instant.fromEpochMilliseconds(date)
}

//@Serializer(forClass = LocalDate::class)
object MongoLocalDateSerializer : TemporalExtendedJsonSerializer<LocalDate>() {

    override fun epochMillis(temporal: LocalDate): Long =
        MongoInstantSerializer.epochMillis(temporal.atStartOfDayIn(TimeZone.UTC))

    override fun instantiate(date: Long): LocalDate =
        MongoLocalDateTimeSerializer.instantiate(date).date
}

//@Serializer(forClass = LocalDateTime::class)
object MongoLocalDateTimeSerializer : TemporalExtendedJsonSerializer<LocalDateTime>() {

    override fun epochMillis(temporal: LocalDateTime): Long =
        MongoInstantSerializer.epochMillis(temporal.toInstant(TimeZone.UTC))

    override fun instantiate(date: Long): LocalDateTime =
        MongoInstantSerializer.instantiate(date).toLocalDateTime(TimeZone.UTC)
}

//@Serializer(forClass = LocalTime::class)
object MongoLocalTimeSerializer : TemporalExtendedJsonSerializer<LocalTime>() {

    override fun epochMillis(temporal: LocalTime): Long =
        MongoLocalDateTimeSerializer.epochMillis(temporal.atDate(LocalDate.fromEpochDays(0)))

    override fun instantiate(date: Long): LocalTime =
        MongoLocalDateTimeSerializer.instantiate(date).time
}

//@Serializer(forClass = Locale::class)
object MongoLocaleSerializer : KSerializer<Locale> {

    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocaleSerializer", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Locale = Locale.forLanguageTag(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: Locale) {
        encoder.encodeString(value.toLanguageTag())
    }
}

@Serializer(forClass = UUID::class)
object UUIDSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.lightningkite.UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder as BsonEncoder
        encoder.encodeUUID(value.toJavaUuid(), UuidRepresentation.STANDARD)
    }

    override fun deserialize(decoder: Decoder): UUID {
        return when (decoder) {
            is FlexibleDecoder -> {
                when (decoder.reader.currentBsonType) {
                    BsonType.STRING -> {
                        java.util.UUID.fromString(decoder.decodeString()).toKotlinUuid()
                    }
                    BsonType.BINARY -> {
                        decoder.reader.readBinaryData().asUuid(UuidRepresentation.STANDARD).toKotlinUuid()
                    }
                    else -> throw SerializationException("Unsupported ${decoder.reader.currentBsonType} reading object id")
                }
            }
            else -> throw SerializationException("Unknown decoder type")
        }
    }
}