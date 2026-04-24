package com.lightningkite.lightningserver.notifications

import kotlinx.datetime.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FrequencyTest {

    @Test
    fun testImmediately() {
        val freq = Frequency.immediately()
        val now = Instant.parse("2024-01-15T12:00:00Z")
        val scheduled = freq.schedule(now)

        assertEquals(now, scheduled, "Immediate frequency should schedule for current time")
    }

    @Test
    fun testDelayed() {
        val freq = Frequency.delayed(30.minutes)
        val now = Instant.parse("2024-01-15T12:00:00Z")
        val scheduled = freq.schedule(now)
        val expected = now + 30.minutes

        assertEquals(expected, scheduled, "Delayed frequency should add delay to current time")
    }

    @Test
    fun testBatch() {
        val freq = Frequency.batch(15) // Every 15 minutes
        val now = Instant.parse("2024-01-15T12:07:00Z") // 7 minutes past the hour
        val scheduled = freq.schedule(now)

        // Should schedule for 12:15 (8 minutes later)
        val expected = Instant.parse("2024-01-15T12:15:00Z")
        assertEquals(expected, scheduled, "Batch should schedule at next 15-minute interval")
    }

    @Test
    fun testBatchExactInterval() {
        val freq = Frequency.batch(30) // Every 30 minutes
        val now = Instant.parse("2024-01-15T12:30:00Z") // Exactly on interval
        val scheduled = freq.schedule(now)

        // Should schedule immediately since we're exactly on the interval
        val expected = Instant.parse("2024-01-15T13:00:00Z")
        assertEquals(expected, scheduled, "Batch at exact interval should schedule for next interval")
    }

    // Fixed: Use TimeZone.UTC for cross-platform compatibility (America/New_York not available on all KMP targets)

    @Test
    fun testDailyBeforeTime() {
        val tz = TimeZone.UTC
        val freq = Frequency.daily(hour = 14, minute = 30, timeZone = tz) // 2:30 PM UTC
        val now = Instant.parse("2024-01-15T10:00:00Z") // 10:00 AM UTC (before 2:30 PM)
        val scheduled = freq.schedule(now)

        // Should schedule for 2:30 PM today UTC
        val expected = LocalDateTime(2024, 1, 15, 14, 30).toInstant(tz)
        assertEquals(expected, scheduled, "Daily should schedule for same day if time hasn't passed")
    }

    @Test
    fun testDailyAfterTime() {
        val tz = TimeZone.UTC
        val freq = Frequency.daily(hour = 14, minute = 30, timeZone = tz) // 2:30 PM UTC
        val now = Instant.parse("2024-01-15T15:00:00Z") // 3:00 PM UTC (after 2:30 PM)
        val scheduled = freq.schedule(now)

        // Should schedule for 2:30 PM tomorrow UTC
        val expected = LocalDateTime(2024, 1, 16, 14, 30).toInstant(tz)
        assertEquals(expected, scheduled, "Daily should schedule for next day if time has passed")
    }

    @Test
    fun testWeeklyForward() {
        val tz = TimeZone.UTC
        // Schedule for Wednesday at 2:30 PM UTC
        val freq = Frequency.weekly(weekDay = DayOfWeek.WEDNESDAY, hour = 14, minute = 30, timeZone = tz)
        // Current time is Monday
        val now = Instant.parse("2024-01-15T10:00:00Z") // Monday Jan 15, 2024 10:00 AM UTC
        assertEquals(DayOfWeek.MONDAY, now.toLocalDateTime(tz).dayOfWeek)
        val scheduled = freq.schedule(now)

        // Should schedule for Wednesday Jan 17, 2024 at 2:30 PM UTC
        val expected = LocalDateTime(2024, 1, 17, 14, 30).toInstant(tz)
        assertEquals(expected, scheduled, "Weekly should schedule for next occurrence of weekday")
    }

    @Test
    fun testDelayedDaily() {
        val tz = TimeZone.UTC
        val freq = Frequency.daily(hour = 14, minute = 30, timeZone = tz).delayed(1.hours)
        val now = Instant.parse("2024-01-15T10:00:00Z") // 10:00 AM UTC
        val scheduled = freq.schedule(now)

        // Should schedule for 2:30 PM today + 1 hour = 3:30 PM UTC
        val expected = LocalDateTime(2024, 1, 15, 14, 30).toInstant(tz) + 1.hours
        assertEquals(expected, scheduled, "Delayed should add to scheduled time")
    }

    @Test
    fun testDailyStringTime() {
        val tz = TimeZone.UTC
        val freq = Frequency.daily(time = "14:30", timeZone = tz)
        val now = Instant.parse("2024-01-15T10:00:00Z")
        val scheduled = freq.schedule(now)

        val expected = LocalDateTime(2024, 1, 15, 14, 30).toInstant(tz)
        assertEquals(expected, scheduled, "Daily with string time should parse correctly")
    }
}
