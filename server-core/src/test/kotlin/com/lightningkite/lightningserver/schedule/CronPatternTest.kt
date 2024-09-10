package com.lightningkite.lightningserver.schedule

import com.lightningkite.nowLocal
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import java.time.DayOfWeek
import kotlin.test.Test
import kotlin.test.assertEquals

class LongBitsTest {
    @Test fun bits() {
        assertEquals(LongBits(0b1101), LongBits(setOf(0, 2, 3)))
        assertEquals(true, LongBits(0b1101).contains(0))
        assertEquals(false, LongBits(0b1101).contains(1))
        assertEquals(true, LongBits(0b1101).contains(2))
        assertEquals(true, LongBits(0b1101).contains(3))
        assertEquals(false, LongBits(0b1101).contains(4))
        assertEquals(listOf(0, 2, 3), LongBits(0b1101).toList())
        assertEquals(0, LongBits(0b1101).lowestIncluding(0))
        assertEquals(2, LongBits(0b1101).lowestAfter(0))
        assertEquals(2, LongBits(0b1101).lowestAfter(1))
        assertEquals(3, LongBits(0b1101).lowestAfter(2))
    }
}

class CronPatternTest {

    fun testPattern(pattern: CronPattern, assertList: String) {
        testPattern(pattern, assertList.lines().filter { it.isNotBlank() }.map { LocalDateTime.parse(it.trim()) })
    }
    fun testPattern(pattern: CronPattern, assertList: List<LocalDateTime>? = null) {
        var now = LocalDateTime(1970, 1, 1, 0, 0)
        println(pattern)
        repeat(10000) {
            now += pattern
            println(now)
            assertList?.getOrNull(it)?.let {
                assertEquals(it, now)
            }
        }
    }

    @Test fun testLots() {
        val pattern = CronPattern(
            minutes = 0..<60 step 5,
        )
        var now = LocalDateTime(1970, 1, 1, 0, 0)
        repeat(40000) {
            now += pattern
        }
        println(now)
    }

    @Test fun testEveryMinute() {
        testPattern(CronPattern(),
            """
            1970-01-01T00:01
            1970-01-01T00:02
            1970-01-01T00:03
            1970-01-01T00:04
            1970-01-01T00:05
            1970-01-01T00:06
            1970-01-01T00:07
            1970-01-01T00:08
            1970-01-01T00:09
            1970-01-01T00:10
            1970-01-01T00:11
            1970-01-01T00:12
            1970-01-01T00:13
            1970-01-01T00:14
            1970-01-01T00:15
            1970-01-01T00:16
            1970-01-01T00:17
            1970-01-01T00:18
            """.trimIndent())
    }


    @Test fun testEveryFiveMinutes() {
        testPattern(CronPattern(
            minutes = 0..<60 step 5,
        ), """
            1970-01-01T00:05
            1970-01-01T00:10
            1970-01-01T00:15
            1970-01-01T00:20
            1970-01-01T00:25
            1970-01-01T00:30
            1970-01-01T00:35
            1970-01-01T00:40
            1970-01-01T00:45
            1970-01-01T00:50
            1970-01-01T00:55
            1970-01-01T01:00
            1970-01-01T01:05
            1970-01-01T01:10
            1970-01-01T01:15
            1970-01-01T01:20
            1970-01-01T01:25
            1970-01-01T01:30
            1970-01-01T01:35
            1970-01-01T01:40
        """.trimIndent())
    }

    @Test fun testDuringDay() {
        testPattern(CronPattern(
            minutes = setOf(0),
            hours = setOf(1,3) + (8..18)
        ))
    }

    @Test fun testEveryThreeHours() {
        testPattern(CronPattern(
            minutes = setOf(0),
            hours = 0..23 step 3,
        ), """
            1970-01-01T03:00
            1970-01-01T06:00
            1970-01-01T09:00
            1970-01-01T12:00
            1970-01-01T15:00
            1970-01-01T18:00
            1970-01-01T21:00
            1970-01-02T00:00
            1970-01-02T03:00
            1970-01-02T06:00
            1970-01-02T09:00
            1970-01-02T12:00
            1970-01-02T15:00
            1970-01-02T18:00
            1970-01-02T21:00
            1970-01-03T00:00
            1970-01-03T03:00
            1970-01-03T06:00
            1970-01-03T09:00
            1970-01-03T12:00

        """.trimIndent())
    }

    @Test fun testEveryDayAtMidnight() {
        testPattern(CronPattern(
            minutes = setOf(0),
            hours = setOf(0),
        ), """
            
            1970-01-02T00:00
            1970-01-03T00:00
            1970-01-04T00:00
            1970-01-05T00:00
            1970-01-06T00:00
            1970-01-07T00:00
            1970-01-08T00:00
            1970-01-09T00:00
            1970-01-10T00:00
            1970-01-11T00:00
            1970-01-12T00:00
            1970-01-13T00:00
            1970-01-14T00:00
            1970-01-15T00:00
            1970-01-16T00:00
            1970-01-17T00:00
            1970-01-18T00:00
            1970-01-19T00:00
            1970-01-20T00:00
            1970-01-21T00:00
        """.trimIndent())
    }

    @Test fun test210EveryMonday() {
        testPattern(CronPattern(
            minutes = setOf(10),
            hours = setOf(14),
            daysOfWeek = setOf(DayOfWeek.MONDAY)
        ), """
            1970-01-05T14:10
            1970-01-12T14:10
            1970-01-19T14:10
            1970-01-26T14:10
            1970-02-02T14:10
            1970-02-09T14:10
            1970-02-16T14:10
            1970-02-23T14:10
            1970-03-02T14:10
            1970-03-09T14:10
            1970-03-16T14:10
            1970-03-23T14:10
            1970-03-30T14:10
            1970-04-06T14:10
            1970-04-13T14:10
            1970-04-20T14:10
            1970-04-27T14:10
            1970-05-04T14:10
            1970-05-11T14:10
            1970-05-18T14:10

        """.trimIndent())
    }

    @Test fun testEveryWeekdayAtMidnight() {
        testPattern(CronPattern(
            minutes = setOf(0),
            hours = setOf(0),
            daysOfWeek = DayOfWeek.MONDAY..DayOfWeek.FRIDAY
        ), """
            1970-01-02T00:00
            1970-01-05T00:00
            1970-01-06T00:00
            1970-01-07T00:00
            1970-01-08T00:00
            1970-01-09T00:00
            1970-01-12T00:00
            1970-01-13T00:00
            1970-01-14T00:00
            1970-01-15T00:00
            1970-01-16T00:00
            1970-01-19T00:00
            1970-01-20T00:00
            1970-01-21T00:00
            1970-01-22T00:00
            1970-01-23T00:00
            1970-01-26T00:00
            1970-01-27T00:00
            1970-01-28T00:00
            1970-01-29T00:00

        """.trimIndent())
    }

    @Test fun testMidnightFirstAndFifteenth() {
        testPattern(CronPattern(
            minutes = setOf(0),
            hours = setOf(0),
            daysOfMonth = setOf(1, 15)
        ), """
            1970-01-15T00:00
            1970-02-01T00:00
            1970-02-15T00:00
            1970-03-01T00:00
            1970-03-15T00:00
            1970-04-01T00:00
            1970-04-15T00:00
            1970-05-01T00:00
            1970-05-15T00:00
            1970-06-01T00:00
            1970-06-15T00:00
            1970-07-01T00:00
            1970-07-15T00:00
            1970-08-01T00:00
            1970-08-15T00:00
            1970-09-01T00:00
            1970-09-15T00:00
            1970-10-01T00:00
            1970-10-15T00:00
            1970-11-01T00:00

        """.trimIndent())
    }

    @Test fun dayOfMonth31() {
        testPattern(CronPattern(
            minutes = setOf(0),
            hours = setOf(0),
            daysOfMonth = setOf(31),
        ), """
            1970-01-31T00:00
            1970-03-31T00:00
            1970-05-31T00:00
            1970-07-31T00:00
            1970-08-31T00:00
            1970-10-31T00:00
            1970-12-31T00:00
            1971-01-31T00:00
            1971-03-31T00:00
            1971-05-31T00:00
            1971-07-31T00:00
            1971-08-31T00:00
            1971-10-31T00:00
            1971-12-31T00:00
            1972-01-31T00:00
            1972-03-31T00:00
            1972-05-31T00:00
            1972-07-31T00:00
            1972-08-31T00:00
            1972-10-31T00:00
        """.trimIndent())
    }

    @Test fun leapDay() {
        testPattern(CronPattern(
            minutes = setOf(0),
            hours = setOf(0),
            months = listOf(Month.FEBRUARY),
            daysOfMonth = setOf(29),
        ), """
            1972-02-29T00:00
            1976-02-29T00:00
            1980-02-29T00:00
            1984-02-29T00:00
            1988-02-29T00:00
            1992-02-29T00:00
            1996-02-29T00:00
            2000-02-29T00:00
            2004-02-29T00:00
            2008-02-29T00:00
            2012-02-29T00:00
            2016-02-29T00:00
            2020-02-29T00:00
            2024-02-29T00:00
            2028-02-29T00:00
            2032-02-29T00:00
            2036-02-29T00:00
            2040-02-29T00:00
            2044-02-29T00:00
            2048-02-29T00:00
        """.trimIndent())
    }


    @Test fun absurd() {
        testPattern(CronPattern(
            minutes = setOf(1,4,6) + (10 .. 30) + (40 .. 59).step(3),
            hours = (1..23).step(3) + (1..23).step(4),
            months = setOf(java.time.Month.JANUARY, java.time.Month.MARCH, java.time.Month.APRIL, java.time.Month.AUGUST, java.time.Month.NOVEMBER),
            daysOfMonth = (1..31).step(2),
        ))
    }
}