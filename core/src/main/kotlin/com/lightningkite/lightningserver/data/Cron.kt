package com.lightningkite.lightningserver.data

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.YearMonth
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

/**
 * Represents a cron-like pattern for scheduling recurring tasks.
 *
 * Supports specifying minutes (0-59), hours (0-23), days (of month or week), and months.
 * Format follows standard cron conventions with some limitations on advanced features.
 *
 * Example:
 * ```kotlin
 * // Every day at 3:30 AM
 * CronPattern(minutes = listOf(30), hours = listOf(3))
 *
 * // Every Monday at 9:00 AM
 * CronPattern(
 *     minutes = listOf(0),
 *     hours = listOf(9),
 *     days = CronDays.DaysOfWeek(DayOfWeek.MONDAY)
 * )
 * ```
 *
 * @property minutes Bit set of valid minutes (0-59)
 * @property hours Bit set of valid hours (0-23)
 * @property days Day specification (all days, specific days of month, or specific days of week)
 * @property months Bit set of valid months (1-12)
 */
public data class CronPattern(
    val minutes: LongBits,
    val hours: LongBits,
    val days: CronDays,
    val months: LongBits,
) {
    /**
     * Convenience constructor that accepts iterables of valid values.
     *
     * @param minutes Valid minute values (0-59). Defaults to every minute.
     * @param hours Valid hour values (0-23). Defaults to every hour.
     * @param days Day specification. Defaults to all days.
     * @param months Valid months. Defaults to all months.
     * @throws IllegalArgumentException if any time component has no valid values
     */
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

    /**
     * Returns a cron-style string representation.
     *
     * Format: `minute hour day-of-month month day-of-week`
     *
     * Note: Uses `?` as a placeholder where appropriate for day fields,
     * following standard cron conventions where day-of-month and day-of-week are mutually exclusive.
     */
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

/**
 * Specifies which days a cron pattern should run on.
 *
 * This sealed interface provides three options:
 * - [All]: Run on all days
 * - [DaysOfMonth]: Run on specific days of the month (1-31)
 * - [DaysOfWeek]: Run on specific days of the week (Monday-Sunday)
 *
 * Note: DaysOfMonth and DaysOfWeek are mutually exclusive in standard cron syntax.
 */
public sealed interface CronDays {
    /** Represents all days (no day restriction). */
    public data object All : CronDays

    /**
     * Represents specific days of the month.
     *
     * @property days Set of day specifications (e.g., Day(15) for the 15th)
     */
    public data class DaysOfMonth(val days: Set<CronDayOfMonth>) : CronDays {
        /** Convenience constructor that accepts day numbers. */
        public constructor(days: Iterable<Int>) : this(days.map(CronDayOfMonth::Day).toSet())
        /** Convenience constructor that accepts day numbers as varargs. */
        public constructor(vararg days: Int) : this(days.map(CronDayOfMonth::Day).toSet())
    }

    /**
     * Represents specific days of the week.
     *
     * @property days Set of weekday specifications
     */
    public data class DaysOfWeek(val days: Set<CronDayOfWeek>) : CronDays {
        /** Convenience constructor that accepts DayOfWeek values. */
        public constructor(days: Iterable<DayOfWeek>) : this(days.map(::CronDayOfWeek).toSet())
        /** Convenience constructor that accepts DayOfWeek values as varargs. */
        public constructor(vararg days: DayOfWeek) : this(days.map(::CronDayOfWeek).toSet())
    }
}

/**
 * Represents a specific day-of-month specification in a cron pattern.
 *
 * Currently only [Day] (specific day number) is fully supported.
 * Advanced features like [Last] and [NearestWeekday] are not yet implemented.
 */
public sealed class CronDayOfMonth {
    /** Last day of the month. */
    @Deprecated("This does not work yet")
    public data object Last : CronDayOfMonth() {
        override fun toString(): String = "L"
    }

    /**
     * A specific day number (1-31).
     *
     * @property number The day of the month (1-31)
     */
    public data class Day(val number: Int) : CronDayOfMonth() {
        override fun toString(): String = number.toString()
    }

    /**
     * The nearest weekday to a given day number.
     *
     * @property number The reference day number
     */
    @Deprecated("This does not work yet")
    public data class NearestWeekday(val number: Int) : CronDayOfMonth() {
        override fun toString(): String = "${number}W"
    }
}

/**
 * Represents a day-of-week specification in a cron pattern.
 *
 * @property day The day of the week
 * @property last Whether this is the last occurrence of this weekday in the month (not yet implemented)
 * @property recurrence Which occurrence in the month (e.g., 2 for "second Monday") (not yet implemented)
 */
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

/**
 * Advances this datetime to the next occurrence matching the cron pattern.
 *
 * If the current datetime already matches the pattern, returns the same datetime.
 * Otherwise, advances to the next valid time according to the pattern.
 *
 * @param pattern The cron pattern to match
 * @return The next datetime that matches the pattern
 */
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
                // TODO: Add support for CronDayOfMonth.Last and CronDayOfMonth.NearestWeekday
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

/**
 * Represents a range of days of the week.
 *
 * Allows creating ranges like `DayOfWeek.MONDAY..DayOfWeek.FRIDAY`.
 *
 * @property start The starting day of the range
 * @property endInclusive The ending day of the range (inclusive)
 */
public class DayOfWeekRange(override val start: DayOfWeek, override val endInclusive: DayOfWeek) : Iterable<DayOfWeek>,
    ClosedRange<DayOfWeek> {
    override fun iterator(): Iterator<DayOfWeek> = DayOfWeek.entries.filter { it in this }.iterator()
}

/**
 * Creates a range from this day of week to another.
 *
 * Example: `DayOfWeek.MONDAY..DayOfWeek.FRIDAY`
 */
public operator fun DayOfWeek.rangeTo(endInclusive: DayOfWeek): DayOfWeekRange = DayOfWeekRange(this, endInclusive)

/*
 * TODO: API Recommendations for Cron.kt
 *
 * 1. Complete implementation of advanced day-of-month features:
 *    - CronDayOfMonth.Last (last day of month)
 *    - CronDayOfMonth.NearestWeekday (nearest weekday to a given day)
 *    - CronDayOfWeek.last and CronDayOfWeek.recurrence (nth occurrence patterns)
 *
 * 2. Add cron string parsing functionality:
 *    - CronPattern.parse(cronString: String): CronPattern
 *    This would allow users to create patterns from standard cron expressions
 *
 * 3. Add validation for day-of-month values (1-31) in CronDayOfMonth.Day constructor
 *    to fail fast on invalid input rather than at pattern execution time
 *
 * 4. Consider adding a nextOccurrence() or getNextRun() method that doesn't modify the
 *    receiver datetime, making the API more explicit:
 *    - fun CronPattern.nextOccurrence(after: LocalDateTime): LocalDateTime
 *
 * 5. Add timezone-aware scheduling support by accepting Instant instead of just LocalDateTime,
 *    to handle DST transitions correctly
 *
 * 6. Consider adding a method to list next N occurrences:
 *    - fun CronPattern.nextOccurrences(after: LocalDateTime, count: Int): List<LocalDateTime>
 */
