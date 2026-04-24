// by Claude
package com.lightningkite.lightningserver.notifications

import kotlinx.datetime.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Tests for [Frequency] scheduling logic.
 * Verifies that all frequency types correctly calculate the next scheduled time.
 */
class FrequencyTest {

    // ===== Immediately Tests =====

    @Test
    fun `immediately schedules for current time`() {
        val now = Instant.parse("2024-01-15T12:00:00Z")
        val scheduled = Frequency.immediately().schedule(now)
        assertEquals(now, scheduled)
    }

    @Test
    fun `immediately schedules same time regardless of when called`() {
        val times = listOf(
            Instant.parse("2024-01-15T00:00:00Z"),
            Instant.parse("2024-01-15T12:00:00Z"),
            Instant.parse("2024-01-15T23:59:59Z")
        )
        for (now in times) {
            assertEquals(now, Frequency.immediately().schedule(now))
        }
    }

    // ===== Delayed Tests =====

    @Test
    fun `delayed adds duration to now`() {
        val now = Instant.parse("2024-01-15T12:00:00Z")
        val delayed = Frequency.delayed(30.minutes)
        val scheduled = delayed.schedule(now)
        assertEquals(now + 30.minutes, scheduled)
    }

    @Test
    fun `delayed with hours works correctly`() {
        val now = Instant.parse("2024-01-15T12:00:00Z")
        val delayed = Frequency.delayed(2.hours)
        val scheduled = delayed.schedule(now)
        assertEquals(now + 2.hours, scheduled)
    }

    @Test
    fun `delayed crossing midnight works correctly`() {
        val now = Instant.parse("2024-01-15T23:30:00Z")
        val delayed = Frequency.delayed(2.hours)
        val scheduled = delayed.schedule(now)
        assertEquals(Instant.parse("2024-01-16T01:30:00Z"), scheduled)
    }

    // ===== Batch Tests =====

    @Test
    fun `batch rounds up to next interval when not on boundary`() {
        val now = Instant.parse("2024-01-15T12:07:00Z")
        val batch = Frequency.batch(15)
        val scheduled = batch.schedule(now)
        assertEquals(Instant.parse("2024-01-15T12:15:00Z"), scheduled)
    }

    @Test
    fun `batch on exact boundary schedules for next interval`() {
        val now = Instant.parse("2024-01-15T12:00:00Z")
        val batch = Frequency.batch(15)
        val scheduled = batch.schedule(now)
        assertEquals(Instant.parse("2024-01-15T12:15:00Z"), scheduled)
    }

    @Test
    fun `batch with 30 minute interval works correctly`() {
        val now = Instant.parse("2024-01-15T12:20:00Z")
        val batch = Frequency.batch(30)
        val scheduled = batch.schedule(now)
        assertEquals(Instant.parse("2024-01-15T12:30:00Z"), scheduled)
    }

    @Test
    fun `batch with 60 minute interval works correctly`() {
        val now = Instant.parse("2024-01-15T12:45:00Z")
        val batch = Frequency.batch(60)
        val scheduled = batch.schedule(now)
        assertEquals(Instant.parse("2024-01-15T13:00:00Z"), scheduled)
    }

    @Test
    fun `batch crossing hour boundary works correctly`() {
        val now = Instant.parse("2024-01-15T12:50:00Z")
        val batch = Frequency.batch(15)
        val scheduled = batch.schedule(now)
        assertEquals(Instant.parse("2024-01-15T13:00:00Z"), scheduled)
    }

    // ===== Daily Tests (UTC) =====

    @Test
    fun `daily schedules for same day if time not passed`() {
        val now = Instant.parse("2024-01-15T08:00:00Z")
        val daily = Frequency.daily(9, 0, TimeZone.UTC)
        val scheduled = daily.schedule(now)
        assertEquals(Instant.parse("2024-01-15T09:00:00Z"), scheduled)
    }

    @Test
    fun `daily schedules for next day if time passed`() {
        val now = Instant.parse("2024-01-15T10:00:00Z")
        val daily = Frequency.daily(9, 0, TimeZone.UTC)
        val scheduled = daily.schedule(now)
        assertEquals(Instant.parse("2024-01-16T09:00:00Z"), scheduled)
    }

    @Test
    fun `daily at exact time schedules for same time`() {
        val now = Instant.parse("2024-01-15T09:00:00Z")
        val daily = Frequency.daily(9, 0, TimeZone.UTC)
        val scheduled = daily.schedule(now)
        // At exact time (sendAt == now, not sendAt < now), it returns same time
        assertEquals(Instant.parse("2024-01-15T09:00:00Z"), scheduled)
    }

    @Test
    fun `daily with minutes works correctly`() {
        val now = Instant.parse("2024-01-15T08:00:00Z")
        val daily = Frequency.daily(9, 30, TimeZone.UTC)
        val scheduled = daily.schedule(now)
        assertEquals(Instant.parse("2024-01-15T09:30:00Z"), scheduled)
    }

    @Test
    fun `daily with string time parsing works`() {
        val now = Instant.parse("2024-01-15T08:00:00Z")
        val daily = Frequency.daily("14:30", TimeZone.UTC)
        val scheduled = daily.schedule(now)
        assertEquals(Instant.parse("2024-01-15T14:30:00Z"), scheduled)
    }

    @Test
    fun `daily with LocalTime works`() {
        val now = Instant.parse("2024-01-15T08:00:00Z")
        val daily = Frequency.daily(TimeZone.UTC, LocalTime(17, 45))
        val scheduled = daily.schedule(now)
        assertEquals(Instant.parse("2024-01-15T17:45:00Z"), scheduled)
    }

    @Test
    fun `daily near midnight handles date change correctly`() {
        val now = Instant.parse("2024-01-15T23:30:00Z")
        val daily = Frequency.daily(2, 0, TimeZone.UTC)
        val scheduled = daily.schedule(now)
        assertEquals(Instant.parse("2024-01-16T02:00:00Z"), scheduled)
    }

    // ===== Weekly Tests (UTC) =====

    @Test
    fun `weekly schedules for correct day of week same week`() {
        // Monday Jan 15, 2024 - schedule for Wednesday
        val now = Instant.parse("2024-01-15T08:00:00Z")
        val weekly = Frequency.weekly(DayOfWeek.WEDNESDAY, 9, 0, TimeZone.UTC)
        val scheduled = weekly.schedule(now)
        assertEquals(Instant.parse("2024-01-17T09:00:00Z"), scheduled)
    }

    @Test
    fun `weekly wraps to next week when day passed`() {
        // Wednesday Jan 17, 2024 - schedule for Monday
        val now = Instant.parse("2024-01-17T08:00:00Z")
        val weekly = Frequency.weekly(DayOfWeek.MONDAY, 9, 0, TimeZone.UTC)
        val scheduled = weekly.schedule(now)
        assertEquals(Instant.parse("2024-01-22T09:00:00Z"), scheduled)
    }

    @Test
    fun `weekly same day but time passed schedules for next week`() {
        // Monday Jan 15, 2024 at 10:00 - schedule for Monday at 9:00
        val now = Instant.parse("2024-01-15T10:00:00Z")
        val weekly = Frequency.weekly(DayOfWeek.MONDAY, 9, 0, TimeZone.UTC)
        val scheduled = weekly.schedule(now)
        assertEquals(Instant.parse("2024-01-22T09:00:00Z"), scheduled)
    }

    @Test
    fun `weekly same day time not passed schedules for same day`() {
        // Monday Jan 15, 2024 at 08:00 - schedule for Monday at 9:00
        val now = Instant.parse("2024-01-15T08:00:00Z")
        val weekly = Frequency.weekly(DayOfWeek.MONDAY, 9, 0, TimeZone.UTC)
        val scheduled = weekly.schedule(now)
        assertEquals(Instant.parse("2024-01-15T09:00:00Z"), scheduled)
    }

    @Test
    fun `weekly handles month boundary correctly`() {
        // January 31, Wednesday
        val now = Instant.parse("2024-01-31T08:00:00Z")
        val weekly = Frequency.weekly(DayOfWeek.FRIDAY, 9, 0, TimeZone.UTC)
        val scheduled = weekly.schedule(now)
        assertEquals(Instant.parse("2024-02-02T09:00:00Z"), scheduled)
    }

    @Test
    fun `weekly handles year boundary correctly`() {
        // December 30, 2024 is Monday
        val now = Instant.parse("2024-12-30T08:00:00Z")
        val weekly = Frequency.weekly(DayOfWeek.FRIDAY, 9, 0, TimeZone.UTC)
        val scheduled = weekly.schedule(now)
        assertEquals(Instant.parse("2025-01-03T09:00:00Z"), scheduled)
    }

    // ===== Chained Delay Tests =====

    @Test
    fun `delayed chained after immediately adds delay`() {
        val now = Instant.parse("2024-01-15T12:00:00Z")
        val freq = Frequency.immediately().delayed(30.minutes)
        val scheduled = freq.schedule(now)
        assertEquals(now + 30.minutes, scheduled)
    }

    @Test
    fun `delayed chained after daily adds delay after daily time`() {
        val now = Instant.parse("2024-01-15T08:00:00Z")
        val freq = Frequency.daily(9, 0, TimeZone.UTC).delayed(1.hours)
        val scheduled = freq.schedule(now)
        assertEquals(Instant.parse("2024-01-15T10:00:00Z"), scheduled)
    }

    @Test
    fun `delayed chained after batch adds delay after batch time`() {
        val now = Instant.parse("2024-01-15T12:07:00Z")
        val freq = Frequency.batch(15).delayed(5.minutes)
        val scheduled = freq.schedule(now)
        assertEquals(Instant.parse("2024-01-15T12:20:00Z"), scheduled)
    }

    // ===== Edge Cases =====

    @Test
    fun `frequency comparison for merging works correctly`() {
        val now = Instant.parse("2024-01-15T12:00:00Z")
        val frequencies = listOf(
            Frequency.daily(9, 0, TimeZone.UTC),
            Frequency.immediately(),
            Frequency.delayed(1.hours)
        )
        val earliest = frequencies.minByOrNull { it.schedule(now) }
        assertEquals(Frequency.immediately(), earliest)
    }

    @Test
    fun `multiple frequencies with same scheduled time are equal`() {
        val now = Instant.parse("2024-01-15T12:00:00Z")
        val f1 = Frequency.immediately()
        val f2 = Frequency.delayed(0.minutes)
        assertEquals(f1.schedule(now), f2.schedule(now))
    }
}
