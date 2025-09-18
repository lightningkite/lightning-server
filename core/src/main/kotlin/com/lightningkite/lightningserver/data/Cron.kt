package com.lightningkite.lightningserver.data

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.YearMonth
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number


public data class CronPattern(
    val minutes: LongBits,
    val hours: LongBits,
    val days: CronDays,
    val months: LongBits,
) {
    public constructor(
        minutes: Iterable<Int> = everyMinute,
        hours: Iterable<Int> = everyHour,
        days: CronDays = CronDays.All,
        months: Iterable<Month> = everyMonth,
    ) : this(
        minutes = LongBits(minutes),
        hours = LongBits(hours),
        days = days,
        months = months.map { it.number }.let(::LongBits),
    ) {
        if (this.minutes.long == 0L) throw IllegalArgumentException("No valid minutes provided")
        if (this.hours.long == 0L) throw IllegalArgumentException("No valid hours provided")
        if (days is CronDays.DaysOfWeek && days.days.isEmpty())
            throw IllegalArgumentException("No valid days of week provided")
        else if (days is CronDays.DaysOfMonth && days.days.isEmpty())
            throw IllegalArgumentException("No valid days of month provided")
        if (this.months.long == 0L) throw IllegalArgumentException("No valid months provided")
    }

    public companion object {
        public val everyMinute: Iterable<Int> = (0..<60)
        public val everyHour: Iterable<Int> = (0..<24)
        public val everyMonth: Iterable<Month> = Month.entries

        private val allMinutes = LongBits(everyMinute)
        private val allHours = LongBits(everyHour)
        private val allMonths = LongBits(everyMonth.map { it.number })
    }

    override fun toString(): String = buildString {
        append(minutes.takeUnless { it == allMinutes }?.toString() ?: "*")
        append(' ')
        append(hours.takeUnless { it == allHours }?.toString() ?: "*")
        append(' ')
        when (days) {
            CronDays.All -> append("*")
            is CronDays.DaysOfMonth -> append(days.days.joinToString(","))
            is CronDays.DaysOfWeek -> append("?")
        }
        append(' ')
        append(months.takeUnless { it == allMonths }?.toString() ?: "*")
        append(' ')
        when (days) {
            CronDays.All -> append("?")
            is CronDays.DaysOfMonth -> append("?")
            is CronDays.DaysOfWeek -> append(days.days.joinToString(","))
        }
    }
}

public sealed interface CronDays {
    public data object All : CronDays

    public data class DaysOfMonth(val days: Set<CronDayOfMonth>) : CronDays {
        public constructor(days: Iterable<Int>) : this(days.map(CronDayOfMonth::Day).toSet())
        public constructor(vararg days: Int) : this(days.map(CronDayOfMonth::Day).toSet())
    }

    public data class DaysOfWeek(val days: Set<CronDayOfWeek>) : CronDays {
        public constructor(days: Iterable<DayOfWeek>) : this(days.map(::CronDayOfWeek).toSet())
        public constructor(vararg days: DayOfWeek) : this(days.map(::CronDayOfWeek).toSet())
    }
}

public sealed class CronDayOfMonth {
    @Deprecated("This does not work yet")
    public data object Last : CronDayOfMonth() {
        override fun toString(): String = "L"
    }

    public data class Day(val number: Int) : CronDayOfMonth() {
        override fun toString(): String = number.toString()
    }

    @Deprecated("This does not work yet")
    public data class NearestWeekday(val number: Int) : CronDayOfMonth() {
        override fun toString(): String = "${number}W"
    }
}

public data class CronDayOfWeek(
    val day: DayOfWeek,
    @Deprecated("This does not work yet")
    val last: Boolean = false,
    @Deprecated("This does not work yet")
    val recurrence: Int? = null,
) {
    @Suppress("Deprecation")
    override fun toString(): String = buildString {
        append(day.isoDayNumber)
        if (last) append('L')
        else recurrence?.let {
            append('#')
            append(it.toString())
        }
    }
}

public operator fun LocalDateTime.plus(pattern: CronPattern): LocalDateTime {
    return LocalDateTime(year, month, day, hour, minute).makeValid(pattern)
}

private fun LocalDateTime.makeValid(pattern: CronPattern): LocalDateTime {
    var year = this.year
    var month = this.month
    var dayOfMonth = day
    var hour = this.hour
    var minute = this.minute

    fun advanceMonth() {
        dayOfMonth = 1
        if (month == Month.DECEMBER) {
            year++
            month = Month.JANUARY
        } else {
            month = Month.entries[month.ordinal + 1]
        }
    }

    // Three Minute cases:
    // All minutes are valid, skip.
    // We have a valid minute already
    // We can increase the minute field alone to a valid minute
    // We must advance to the next hour and reset the minute

    run {
        val it = pattern.minutes.lowestAfter(minute)
        if (it == -1) {
            minute = pattern.minutes.first()
            hour++
        } else {
            minute = it
        }
    }

    // Hour Cases
    // We have a valid hour already
    // We can increase the hour field alone to a valid hour and reset the minute
    // We must advance to the next day and reset the hour and minute

    run {
        val allowedHours = pattern.hours
        if (allowedHours.contains(hour)) {
            // We're good to go!
        } else {
            minute = pattern.minutes.first()
            allowedHours.find { it > hour }?.let {
                hour = it
            } ?: run {
                hour = allowedHours.first()
                dayOfMonth++
                if (dayOfMonth > YearMonth(year, month).numberOfDays) {
                    advanceMonth()
                }
            }
        }
    }

    // Day cases:
    // We have a valid weekday
    // We have a valid day of month
    // We can advance to a valid day of month OR valid weekday, whichever is nearest
    // We must advance to the next month and reset

    while (true) {
        if (month.number !in pattern.months) {
            val it = pattern.months.lowestAfter(month.number)
            if (it != -1) {
                month = Month(it)
                dayOfMonth = 1
            } else {
                month = pattern.months.first().let(::Month)
                dayOfMonth = 1
                year++
            }
            continue
        }

        when (val days = pattern.days) {
            CronDays.All -> break
            is CronDays.DaysOfMonth -> {
                // TODO: Full support
                val validDays = days.days.mapNotNull { (it as? CronDayOfMonth.Day)?.number }.sorted()
                if (dayOfMonth in validDays) break

                minute = pattern.minutes.first()
                hour = pattern.hours.first()
                dayOfMonth = validDays.find { it > dayOfMonth } ?: 32

                if (dayOfMonth > YearMonth(year, month).numberOfDays) {
                    advanceMonth()
                    continue
                }
            }

            is CronDays.DaysOfWeek -> {
                val weekday = LocalDate(year, month, dayOfMonth).dayOfWeek
                val allowed = days.days.map { it.day }.sorted()

                val advanceDaysBy = allowed.find { it >= weekday }?.let {
                    it.isoDayNumber - weekday.isoDayNumber
                } ?: ((DayOfWeek.SUNDAY.isoDayNumber - weekday.isoDayNumber) + allowed.first().isoDayNumber)

                minute = pattern.minutes.first()
                hour = pattern.hours.first()
                dayOfMonth += advanceDaysBy

                if (dayOfMonth > YearMonth(year, month).numberOfDays) {
                    advanceMonth()
                    continue
                }
            }
        }
        break
    }

    return LocalDateTime(year, month, dayOfMonth, hour, minute)
}

public class DayOfWeekRange(override val start: DayOfWeek, override val endInclusive: DayOfWeek) : Iterable<DayOfWeek>,
    ClosedRange<DayOfWeek> {
    override fun iterator(): Iterator<DayOfWeek> = DayOfWeek.entries.filter { it in this }.iterator()
}

public operator fun DayOfWeek.rangeTo(endInclusive: DayOfWeek): DayOfWeekRange = DayOfWeekRange(this, endInclusive)
