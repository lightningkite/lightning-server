package com.lightningkite.lightningdb

import com.lightningkite.*
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Duration


val ClientModule = SerializersModule {
    contextual(UUID::class, UUIDSerializer)
    contextual(Instant::class, InstantIso8601Serializer)
    contextual(LocalDate::class, LocalDateIso8601Serializer)
    contextual(LocalTime::class, LocalTimeIso8601Serializer)
    contextual(LocalDateTime::class, LocalDateTimeIso8601Serializer)
    contextual(OffsetDateTime::class, OffsetDateTimeIso8601Serializer)
    contextual(ZonedDateTime::class, ZonedDateTimeIso8601Serializer)
    contextual(Duration::class, DurationSerializer)
    contextual(ServerFile::class, ServerFileSerialization)
}
