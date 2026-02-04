package com.lightningkite.lightningserver.data

import kotlinx.datetime.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CronTest {
    @Test
    fun testEveryMinute() {
        val pattern = CronPattern(
            minutes = (0..<60),
            hours = listOf(10),
            days = CronDays.All,
            months = listOf(Month.JANUARY)
        )

        // The + operator advances to the next valid time, even if current time matches
        val start = LocalDateTime(2024, 1, 15, 10, 5, 0)
        val next = start + pattern
        // Since the current time already matches the pattern (Jan 15, 10:05),
        // it should stay the same or advance minimally
        assertTrue(next >= start)
        assertEquals(10, next.hour)
        assertEquals(Month.JANUARY, next.month)
    }

    @Test
    fun testHourlyAt30Minutes() {
        val pattern = CronPattern(
            minutes = listOf(30),
            hours = (0..<24),
            days = CronDays.All,
            months = Month.entries
        )

        val start = LocalDateTime(2024, 1, 15, 10, 0)
        val next = start + pattern
        assertEquals(LocalDateTime(2024, 1, 15, 10, 30), next)
    }

    @Test
    fun testDailyAt3AM() {
        val pattern = CronPattern(
            minutes = listOf(0),
            hours = listOf(3),
            days = CronDays.All,
            months = Month.entries
        )

        val start = LocalDateTime(2024, 1, 15, 10, 0)
        val next = start + pattern
        assertEquals(LocalDateTime(2024, 1, 16, 3, 0), next)
    }

    @Test
    fun testSpecificDaysOfMonth() {
        val pattern = CronPattern(
            minutes = listOf(0),
            hours = listOf(9),
            days = CronDays.DaysOfMonth(1, 15),
            months = Month.entries
        )

        val start = LocalDateTime(2024, 1, 10, 10, 0)
        val next = start + pattern
        assertEquals(LocalDateTime(2024, 1, 15, 9, 0), next)
    }

    @Test
    fun testWeekdays() {
        val pattern = CronPattern(
            minutes = listOf(0),
            hours = listOf(9),
            days = CronDays.DaysOfWeek(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
            months = Month.entries
        )

        // January 15, 2024 is a Monday
        val start = LocalDateTime(2024, 1, 15, 10, 0)
        val next = start + pattern
        // Should go to Friday January 19
        assertEquals(LocalDateTime(2024, 1, 19, 9, 0), next)
    }

    @Test
    fun testCrossMonthBoundary() {
        val pattern = CronPattern(
            minutes = listOf(0),
            hours = listOf(9),
            days = CronDays.All,
            months = listOf(Month.FEBRUARY)
        )

        val start = LocalDateTime(2024, 1, 31, 10, 0)
        val next = start + pattern
        assertEquals(LocalDateTime(2024, 2, 1, 9, 0), next)
    }

    @Test
    fun testCrossYearBoundary() {
        val pattern = CronPattern(
            minutes = listOf(0),
            hours = listOf(0),
            days = CronDays.All,
            months = listOf(Month.JANUARY)
        )

        val start = LocalDateTime(2024, 12, 31, 23, 0)
        val next = start + pattern
        assertEquals(LocalDateTime(2025, 1, 1, 0, 0), next)
    }

    @Test
    fun testValidationNoMinutes() {
        assertFailsWith<IllegalArgumentException> {
            CronPattern(
                minutes = emptyList(),
                hours = listOf(0),
                days = CronDays.All,
                months = Month.entries
            )
        }
    }

    @Test
    fun testValidationNoHours() {
        assertFailsWith<IllegalArgumentException> {
            CronPattern(
                minutes = listOf(0),
                hours = emptyList(),
                days = CronDays.All,
                months = Month.entries
            )
        }
    }

    @Test
    fun testValidationNoDays() {
        assertFailsWith<IllegalArgumentException> {
            CronPattern(
                minutes = listOf(0),
                hours = listOf(0),
                days = CronDays.DaysOfMonth(emptyList()),
                months = Month.entries
            )
        }
    }

    @Test
    fun testToString() {
        val pattern = CronPattern(
            minutes = listOf(0, 30),
            hours = listOf(9),
            days = CronDays.DaysOfWeek(DayOfWeek.MONDAY),
            months = listOf(Month.JANUARY)
        )
        val str = pattern.toString()
        // Format: minute hour day-of-month month day-of-week
        // Should have 5 space-separated fields
        assertEquals(5, str.split(' ').size)
    }

    @Test
    fun testDayOfWeekRange() {
        val range = DayOfWeek.MONDAY..DayOfWeek.FRIDAY
        val list = range.toList()
        assertEquals(5, list.size)
        assertEquals(DayOfWeek.MONDAY, list.first())
        assertEquals(DayOfWeek.FRIDAY, list.last())
    }

    @Test
    fun testMultipleHoursInDay() {
        val pattern = CronPattern(
            minutes = listOf(0),
            hours = listOf(9, 17),  // 9 AM and 5 PM
            days = CronDays.All,
            months = Month.entries
        )

        val start = LocalDateTime(2024, 1, 15, 10, 0)
        val next = start + pattern
        assertEquals(LocalDateTime(2024, 1, 15, 17, 0), next)
    }

    // ========== Additional validation tests (by Claude) ==========

    @Test
    fun `testValidationNoMonths`() {
        // by Claude
        assertFailsWith<IllegalArgumentException> {
            CronPattern(
                minutes = listOf(0),
                hours = listOf(0),
                days = CronDays.All,
                months = emptyList()
            )
        }
    }

    @Test
    fun `testValidationNoDaysOfWeek`() {
        // by Claude
        assertFailsWith<IllegalArgumentException> {
            CronPattern(
                minutes = listOf(0),
                hours = listOf(0),
                days = CronDays.DaysOfWeek(emptyList<DayOfWeek>()),
                months = Month.entries
            )
        }
    }

    @Test
    fun `testCronDayOfMonthToString`() {
        // by Claude
        val day = CronDayOfMonth.Day(15)
        assertEquals("15", day.toString())
    }

    @Test
    fun `testCronDayOfWeekToString`() {
        // by Claude
        val dow = CronDayOfWeek(DayOfWeek.MONDAY)
        assertEquals("1", dow.toString())  // ISO day number: Monday = 1
    }

    @Test
    fun `testDaysOfMonthFromIntRange`() {
        // by Claude
        val days = CronDays.DaysOfMonth(listOf(1, 15, 28))
        assertEquals(3, days.days.size)
    }

    @Test
    fun `testFebruaryBoundary`() {
        // by Claude - test for day 31 in pattern when Feb only has 28/29 days
        val pattern = CronPattern(
            minutes = listOf(0),
            hours = listOf(9),
            days = CronDays.DaysOfMonth(31),
            months = Month.entries
        )

        // Start in February, which doesn't have 31 days
        val start = LocalDateTime(2024, 2, 1, 10, 0)
        val next = start + pattern
        // Should advance to March 31
        assertEquals(LocalDateTime(2024, 3, 31, 9, 0), next)
    }

    @Test
    fun `testWeekdayPatternWrapToNextWeek`() {
        // by Claude - test weekday wrapping to next week
        val pattern = CronPattern(
            minutes = listOf(0),
            hours = listOf(9),
            days = CronDays.DaysOfWeek(DayOfWeek.MONDAY),
            months = Month.entries
        )

        // January 17, 2024 is a Wednesday
        val start = LocalDateTime(2024, 1, 17, 10, 0)
        val next = start + pattern
        // Should go to next Monday, January 22
        assertEquals(LocalDateTime(2024, 1, 22, 9, 0), next)
    }

}
