package com.lightningkite.lightningserver.schedule

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import java.time.Year


@JvmInline
value class LongBits(val long: Long) : Iterable<Int> {
    constructor(iterable: Iterable<Int>) : this(
        iterable.fold(0x0L) { acc, index ->
            acc or (1L shl index)
        }
    )

    operator fun plus(other: LongBits): LongBits = LongBits(long or other.long)
    fun lowestIncluding(index: Int): Int {
        var result = index
        var current = long ushr index
        while (current != 0L) {
            if (current % 2L == 1L) return result
            result++
            current = current ushr 1
        }
        return -1
    }

    fun lowestAfter(index: Int): Int = lowestIncluding(index + 1)

    override fun iterator(): Iterator<Int> {
        return object : Iterator<Int> {
            var index = 0
            var num = long

            override fun hasNext(): Boolean {
                return num != 0L
            }

            override fun next(): Int {
                while (num != 0L) {
                    if (num % 2L == 1L) {
                        num = num shr 1
                        return index++

                    }
                    num = num shr 1
                    index++
                }
                return -1
            }
        }
    }

    operator fun contains(index: Int): Boolean = (long and (1L shl index)) > 0L
    override fun toString(): String = buildString {
        var wasOn = false
        var start = -1
        var needsComma = false
        for (i in 0..64) {
            val on = contains(i)
            if (on && !wasOn) {
                start = i
            } else if (!on && wasOn) {
                if (start == i - 1) {
                    if (needsComma) append(',') else needsComma = true
                    append(start)
                } else {
                    if (needsComma) append(',') else needsComma = true
                    append(start)
                    append('-')
                    append(i - 1)
                }
            }
            wasOn = on
        }
    }
}

data class CronPattern(
    val minutes: LongBits,
    val hours: LongBits,
    val days: CronDays,
    val months: LongBits,
) {
    constructor(
        minutes: Iterable<Int>? = null,
        hours: Iterable<Int>? = null,
        daysOfMonth: Iterable<Int>? = null,
        daysOfWeek: Iterable<DayOfWeek>? = null,
        months: Iterable<Month>? = null,
    ) : this(
        minutes = minutes?.let(::LongBits) ?: totalminutes,
        hours = hours?.let(::LongBits) ?: totalhours,
        days = daysOfMonth?.let {
            if (daysOfWeek != null) throw IllegalArgumentException("You can't provide both daysOfMonth and daysOfWeek.")
            CronDays.DaysOfMonth(it.map {
                CronDayOfMonth.Day(it)
            }.toSet())
        }
            ?: daysOfWeek?.let { CronDays.DaysOfWeek(it.map { CronDayOfWeek(it) }.toSet()) }
            ?: CronDays.All,
        months = months?.map { it.value }?.let(::LongBits) ?: totalmonths,
    ) {
        if (this.minutes.long == 0L) throw IllegalArgumentException("No valid minutes provided")
        if (this.hours.long == 0L) throw IllegalArgumentException("No valid hours provided")
        if (days is CronDays.DaysOfWeek && days.days.isEmpty())
            throw IllegalArgumentException("No valid days of week provided")
        else if (days is CronDays.DaysOfMonth && days.days.isEmpty())
            throw IllegalArgumentException("No valid days of month provided")
        if (this.months.long == 0L) throw IllegalArgumentException("No valid months provided")
    }

    companion object {
        private val totalminutes = LongBits(0..<60)
        private val totalhours = LongBits(0..<24)
        private val totalmonths = LongBits(1..12)
    }

    override fun toString(): String = buildString {
        append(minutes.takeUnless { it == totalminutes }?.toString() ?: "*")
        append(' ')
        append(hours.takeUnless { it == totalhours }?.toString() ?: "*")
        append(' ')
        when (days) {
            CronDays.All -> append("*")
            is CronDays.DaysOfMonth -> append(days.days.joinToString(","))
            is CronDays.DaysOfWeek -> append("?")
        }
        append(' ')
        append(months.takeUnless { it == totalmonths }?.toString() ?: "*")
        append(' ')
        when (days) {
            CronDays.All -> append("?")
            is CronDays.DaysOfMonth -> append("?")
            is CronDays.DaysOfWeek -> append(days.days.joinToString(","))
        }
    }
}

sealed class CronDayOfMonth {
    @Deprecated("This does not work yet")
    data object Last : CronDayOfMonth() {
        override fun toString(): String = "L"
    }

    data class Day(val number: Int) : CronDayOfMonth() {
        override fun toString(): String = number.toString()
    }

    @Deprecated("This does not work yet")
    data class NearestWeekday(val number: Int) : CronDayOfMonth() {
        override fun toString(): String = "${number}W"
    }
}

data class CronDayOfWeek(
    val day: DayOfWeek,
    @Deprecated("This does not work yet")
    val last: Boolean = false,
    @Deprecated("This does not work yet")
    val recurrence: Int? = null,
) {
    override fun toString(): String = buildString {
        append(day.value)
        if (last) append('L')
        else recurrence?.let {
            append('#')
            append(it.toString())
        }
    }
}

sealed class CronDays {
    data object All : CronDays()
    data class DaysOfMonth(val days: Set<CronDayOfMonth>) : CronDays()
    data class DaysOfWeek(val days: Set<CronDayOfWeek>) : CronDays()
}

operator fun LocalDateTime.plus(pattern: CronPattern): LocalDateTime {
    return LocalDateTime(year, month, dayOfMonth, hour, minute).makeValid(pattern)
}

private fun LocalDateTime.makeValid(pattern: CronPattern): LocalDateTime {

    var year = this.year
    var month = this.month
    var dayOfMonth = this.dayOfMonth
    var hour = this.hour
    var minute = this.minute

    fun advanceMonth() {
        dayOfMonth = 1
        if (month == Month.DECEMBER) {
            year++
            month = Month.JANUARY
        } else {
            month += 1
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
                if (dayOfMonth > month.length(Year.isLeap(year.toLong()))) {
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
        if (month.value !in pattern.months) {
            val it = pattern.months.lowestAfter(month.value)
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

                if (dayOfMonth > month.length(Year.isLeap(year.toLong()))) {
                    advanceMonth()
                    continue
                }
            }

            is CronDays.DaysOfWeek -> {
                val weekday = LocalDate(year, month, dayOfMonth).dayOfWeek
                val allowed = days.days.map { it.day }.sorted()

                val advanceDaysBy = allowed.find { it >= weekday }?.let {
                    it.value - weekday.value
                } ?: ((DayOfWeek.SUNDAY.value - weekday.value) + allowed.first().value)

                minute = pattern.minutes.first()
                hour = pattern.hours.first()
                dayOfMonth += advanceDaysBy

                if (dayOfMonth > month.length(Year.isLeap(year.toLong()))) {
                    advanceMonth()
                    continue
                }
            }
        }
        break
    }

    return LocalDateTime(year, month, dayOfMonth, hour, minute)
}

class DayOfWeekRange(override val start: DayOfWeek, override val endInclusive: DayOfWeek) : Iterable<DayOfWeek>,
    ClosedRange<DayOfWeek> {
    override fun iterator(): Iterator<DayOfWeek> = DayOfWeek.entries.filter { it in this }.iterator()
}

operator fun DayOfWeek.rangeTo(endInclusive: DayOfWeek) = DayOfWeekRange(this, endInclusive)
