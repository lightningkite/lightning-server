// by Claude
package com.lightningkite.lightningserver.data

import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for Schedule sealed class and its subclasses.
 */
class ScheduleTest {

    // ============================================
    // Schedule.Frequency Tests
    // ============================================

    @Test
    fun frequency_accepts_valid_durations_at_minimum_threshold() {
        // Exactly 1 minute should be allowed
        val schedule = Schedule.Frequency(gap = 1.minutes)
        assertEquals(1.minutes, schedule.gap)
    }

    @Test
    fun frequency_accepts_durations_greater_than_minimum() {
        val schedule = Schedule.Frequency(gap = 5.minutes)
        assertEquals(5.minutes, schedule.gap)
    }

    @Test
    fun frequency_accepts_hourly_durations() {
        val schedule = Schedule.Frequency(gap = 1.hours)
        assertEquals(1.hours, schedule.gap)
    }

    @Test
    fun frequency_rejects_zero_duration() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Schedule.Frequency(gap = 0.seconds)
        }
        assertTrue(exception.message?.contains("positive") == true)
    }

    @Test
    fun frequency_rejects_negative_duration() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Schedule.Frequency(gap = (-5).minutes)
        }
        assertTrue(exception.message?.contains("positive") == true)
    }

    @Test
    fun frequency_rejects_duration_less_than_one_minute() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Schedule.Frequency(gap = 59.seconds)
        }
        assertTrue(exception.message?.contains("one minute") == true)
    }

    @Test
    fun frequency_rejects_sub_second_durations() {
        val exception = assertFailsWith<IllegalArgumentException> {
            Schedule.Frequency(gap = 500.milliseconds)
        }
        // Should fail on the "positive" check first or "one minute" check
        assertTrue(exception.message != null)
    }

    @Test
    fun frequency_data_class_equality_works_correctly() {
        val schedule1 = Schedule.Frequency(gap = 5.minutes)
        val schedule2 = Schedule.Frequency(gap = 5.minutes)
        val schedule3 = Schedule.Frequency(gap = 10.minutes)

        assertEquals(schedule1, schedule2)
        assertNotEquals(schedule1, schedule3)
    }

    @Test
    fun frequency_copy_works_correctly() {
        val original = Schedule.Frequency(gap = 5.minutes)
        val copied = original.copy(gap = 10.minutes)

        assertEquals(5.minutes, original.gap)
        assertEquals(10.minutes, copied.gap)
    }

    // ============================================
    // Schedule.Daily Tests
    // ============================================

    @Test
    fun daily_can_be_created_with_time_only() {
        val schedule = Schedule.Daily(time = LocalTime(9, 0))
        assertEquals(LocalTime(9, 0), schedule.time)
        // Uses system default timezone
        assertEquals(TimeZone.currentSystemDefault(), schedule.zone)
    }

    @Test
    fun daily_can_be_created_with_time_and_timezone() {
        val utc = TimeZone.UTC
        val schedule = Schedule.Daily(time = LocalTime(14, 30), zone = utc)
        assertEquals(LocalTime(14, 30), schedule.time)
        assertEquals(utc, schedule.zone)
    }

    @Test
    fun daily_handles_midnight_correctly() {
        val schedule = Schedule.Daily(time = LocalTime(0, 0))
        assertEquals(LocalTime(0, 0), schedule.time)
    }

    @Test
    fun daily_handles_end_of_day_correctly() {
        val schedule = Schedule.Daily(time = LocalTime(23, 59, 59))
        assertEquals(LocalTime(23, 59, 59), schedule.time)
    }

    @Test
    fun daily_data_class_equality_works_correctly() {
        val schedule1 = Schedule.Daily(time = LocalTime(9, 0), zone = TimeZone.UTC)
        val schedule2 = Schedule.Daily(time = LocalTime(9, 0), zone = TimeZone.UTC)
        val schedule3 = Schedule.Daily(time = LocalTime(10, 0), zone = TimeZone.UTC)

        assertEquals(schedule1, schedule2)
        assertNotEquals(schedule1, schedule3)
    }

    @Test
    fun daily_with_different_timezones_are_not_equal() {
        val schedule1 = Schedule.Daily(time = LocalTime(9, 0), zone = TimeZone.UTC)
        val schedule2 = Schedule.Daily(time = LocalTime(9, 0), zone = TimeZone.of("America/New_York"))

        assertNotEquals(schedule1, schedule2)
    }

    // ============================================
    // Schedule.Cron Tests
    // ============================================

    @Test
    fun cron_can_be_created_with_pattern_only() {
        val pattern = CronPattern(minutes = listOf(0), hours = listOf(9))
        val schedule = Schedule.Cron(cron = pattern)
        assertEquals(pattern, schedule.cron)
        assertEquals(TimeZone.currentSystemDefault(), schedule.zone)
    }

    @Test
    fun cron_can_be_created_with_pattern_and_timezone() {
        val pattern = CronPattern(minutes = listOf(30), hours = listOf(14))
        val utc = TimeZone.UTC
        val schedule = Schedule.Cron(cron = pattern, zone = utc)
        assertEquals(pattern, schedule.cron)
        assertEquals(utc, schedule.zone)
    }

    @Test
    fun cron_data_class_equality_works_correctly() {
        val pattern = CronPattern(minutes = listOf(0), hours = listOf(9))
        val schedule1 = Schedule.Cron(cron = pattern, zone = TimeZone.UTC)
        val schedule2 = Schedule.Cron(cron = pattern, zone = TimeZone.UTC)

        assertEquals(schedule1, schedule2)
    }

    @Test
    fun cron_with_different_timezones_are_not_equal() {
        val pattern = CronPattern(minutes = listOf(0), hours = listOf(9))
        val schedule1 = Schedule.Cron(cron = pattern, zone = TimeZone.UTC)
        val schedule2 = Schedule.Cron(cron = pattern, zone = TimeZone.of("America/New_York"))

        assertNotEquals(schedule1, schedule2)
    }

    // ============================================
    // Sealed Class Type Tests
    // ============================================

    @Test
    fun frequency_is_a_schedule() {
        val schedule: Schedule = Schedule.Frequency(gap = 5.minutes)
        assertIs<Schedule.Frequency>(schedule)
    }

    @Test
    fun daily_is_a_schedule() {
        val schedule: Schedule = Schedule.Daily(time = LocalTime(9, 0))
        assertIs<Schedule.Daily>(schedule)
    }

    @Test
    fun cron_is_a_schedule() {
        val pattern = CronPattern(minutes = listOf(0), hours = listOf(9))
        val schedule: Schedule = Schedule.Cron(cron = pattern)
        assertIs<Schedule.Cron>(schedule)
    }

    @Test
    fun when_expression_covers_all_schedule_types() {
        val schedules: List<Schedule> = listOf(
            Schedule.Frequency(gap = 5.minutes),
            Schedule.Daily(time = LocalTime(9, 0)),
            Schedule.Cron(cron = CronPattern(minutes = listOf(0), hours = listOf(9)))
        )

        for (schedule in schedules) {
            val description = when (schedule) {
                is Schedule.Frequency -> "Every ${schedule.gap}"
                is Schedule.Daily -> "Daily at ${schedule.time}"
                is Schedule.Cron -> "Cron pattern"
            }
            assertTrue(description.isNotEmpty())
        }
    }
}
