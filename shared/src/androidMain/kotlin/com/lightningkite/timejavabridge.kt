package com.lightningkite

import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toJavaZoneId
import kotlinx.datetime.toJavaZoneOffset

fun ZonedDateTime.toJavaZonedDateTime(): java.time.ZonedDateTime = java.time.ZonedDateTime.of(
    dateTime.toJavaLocalDateTime(),
    zone.toJavaZoneId()
)
fun OffsetDateTime.toJavaOffsetDateTime(): java.time.OffsetDateTime = java.time.OffsetDateTime.of(
    dateTime.toJavaLocalDateTime(),
    offset.toJavaZoneOffset()
)