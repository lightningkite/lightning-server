package com.lightningkite.lightningdb

import com.lightningkite.OffsetDateTime
import com.lightningkite.ZonedDateTime
import com.lightningkite.nowLocal
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeTest {
    @Test fun testZoned() {
        val x = nowLocal()
        TimeZone.availableZoneIds.forEach { zoneId ->
            val zone = TimeZone.of(zoneId)
            val y = x.copy(zone = zone)
            assertEquals(y, ZonedDateTime.parse(y.toString().also { println(it) }))
        }
    }
    @Test fun testOffset() {
        val x = nowLocal().toOffsetDateTime()
        (-12..12).forEach { hours ->
            val offset = UtcOffset(hours)
            val y = x.copy(offset = offset)
            assertEquals(y, OffsetDateTime.parse(y.toString().also { println(it) }))
        }
    }
}