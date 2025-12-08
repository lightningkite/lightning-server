package com.lightningkite.lightningserver.data

import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Represents different types of scheduling patterns for recurring tasks.
 *
 * This sealed class provides three scheduling strategies:
 * - [Frequency]: Run at fixed intervals
 * - [Daily]: Run once per day at a specific time
 * - [Cron]: Run based on a cron pattern
 */
public sealed class Schedule {
    /**
     * Schedule that runs at fixed time intervals.
     *
     * Example:
     * ```kotlin
     * Schedule.Frequency(gap = 5.minutes)  // Run every 5 minutes
     * ```
     *
     * @property gap The duration between executions
     */
    public data class Frequency(val gap: Duration) : Schedule() {
        init {
            require(gap.isPositive()) { "Frequency gap must be positive." }
            require(gap >= 1.minutes) { "Frequency gap must be one minute or more." }
        }
    }

    /**
     * Schedule that runs once per day at a specific time.
     *
     * Example:
     * ```kotlin
     * Schedule.Daily(
     *     time = LocalTime(3, 0),  // 3:00 AM
     *     zone = TimeZone.of("America/New_York")
     * )
     * ```
     *
     * @property time The local time to run at each day
     * @property zone The timezone for interpreting the time. Defaults to system default.
     */
    public data class Daily(val time: LocalTime, val zone: TimeZone = TimeZone.currentSystemDefault()) : Schedule()

    /**
     * Schedule based on a cron pattern.
     *
     * Provides the most flexibility for complex scheduling needs.
     *
     * Example:
     * ```kotlin
     * Schedule.Cron(
     *     cron = CronPattern(
     *         minutes = listOf(0),
     *         hours = listOf(9, 17),  // 9 AM and 5 PM
     *         days = CronDays.DaysOfWeek(DayOfWeek.MONDAY..DayOfWeek.FRIDAY)
     *     ),
     *     zone = TimeZone.UTC
     * )
     * ```
     *
     * @property cron The cron pattern defining when to run
     * @property zone The timezone for interpreting the cron pattern. Defaults to system default.
     */
    public data class Cron(val cron: CronPattern, val zone: TimeZone = TimeZone.currentSystemDefault()) : Schedule()
}
