package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.data.CronPattern
import com.lightningkite.lightningserver.data.Schedule
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Represents a task that runs on a schedule.
 *
 * Scheduled tasks are registered in [ServerBuilder] and executed automatically by the server
 * runtime based on their [schedule]. Common use cases include cleanup jobs, report generation,
 * data synchronization, and other periodic maintenance operations.
 *
 * @property schedule The schedule defining when this task should run
 * @property timeout Maximum duration this task is allowed to run before being cancelled. Defaults to 5 minutes.
 * @see StartupTask
 * @see Task
 */
public interface ScheduledTask {
    public val schedule: Schedule
    public val timeout: Duration get() = 5.minutes
    context(server: ServerRuntime)
    public suspend fun execute()
}

/**
 * Creates a [ScheduledTask] with a custom [Schedule].
 *
 * This is the most flexible factory function, accepting any [Schedule] implementation.
 *
 * @param schedule The schedule defining when this task should run
 * @param timeout Maximum duration allowed for task execution
 * @param handler The suspending function to execute on schedule, with access to [ServerRuntime]
 * @return A new [ScheduledTask] instance
 */
public fun ScheduledTask(
    schedule: Schedule,
    timeout: Duration = 5.minutes,
    handler: suspend context(ServerRuntime) () -> Unit
): ScheduledTask =
    object : ScheduledTask {
        override val schedule: Schedule = schedule
        override val timeout: Duration = timeout
        context(server: ServerRuntime)
        override suspend fun execute() {
            handler(server)
        }
    }

/**
 * Creates a [ScheduledTask] that runs at a fixed frequency interval.
 *
 * The task will run repeatedly with the specified duration between executions.
 *
 * @param frequency How often the task should run (e.g., `10.minutes`, `1.hours`)
 * @param timeout Maximum duration allowed for task execution
 * @param handler The suspending function to execute on schedule, with access to [ServerRuntime]
 * @return A new [ScheduledTask] instance
 */
public fun ScheduledTask(
    frequency: Duration,
    timeout: Duration = 5.minutes,
    handler: suspend context(ServerRuntime) () -> Unit
): ScheduledTask =
    object : ScheduledTask {
        override val schedule: Schedule = Schedule.Frequency(frequency)
        override val timeout: Duration = timeout
        context(server: ServerRuntime)
        override suspend fun execute() {
            handler(server)
        }
    }

/**
 * Creates a [ScheduledTask] that runs daily at a specific time.
 *
 * The task will execute once per day at the specified time in the given time zone.
 *
 * @param timeOfDay The time of day when the task should run
 * @param timeZone The time zone for the scheduled time
 * @param timeout Maximum duration allowed for task execution
 * @param handler The suspending function to execute on schedule, with access to [ServerRuntime]
 * @return A new [ScheduledTask] instance
 */
public fun ScheduledTask(
    timeOfDay: LocalTime,
    timeZone: TimeZone,
    timeout: Duration = 5.minutes,
    handler: suspend context(ServerRuntime) () -> Unit
): ScheduledTask =
    object : ScheduledTask {
        override val schedule: Schedule = Schedule.Daily(timeOfDay, timeZone)
        override val timeout: Duration = timeout
        context(server: ServerRuntime)
        override suspend fun execute() {
            handler(server)
        }
    }

/**
 * Creates a [ScheduledTask] using a cron expression.
 *
 * Provides the most flexible scheduling using standard cron syntax for complex schedules
 * like "every Monday at 3am" or "first day of each month".
 *
 * @param cron The cron pattern defining when the task should run
 * @param timeZone The time zone for the cron schedule
 * @param timeout Maximum duration allowed for task execution
 * @param handler The suspending function to execute on schedule, with access to [ServerRuntime]
 * @return A new [ScheduledTask] instance
 */
public fun ScheduledTask(
    cron: CronPattern,
    timeZone: TimeZone,
    timeout: Duration = 5.minutes,
    handler: suspend context(ServerRuntime) () -> Unit
): ScheduledTask =
    object : ScheduledTask {
        override val schedule: Schedule = Schedule.Cron(cron, timeZone)
        override val timeout: Duration = timeout
        context(server: ServerRuntime)
        override suspend fun execute() {
            handler(server)
        }
    }

/*
 * TODO: API Recommendations for ScheduledTask.kt
 *
 * 1. Add support for missed execution handling:
 *    - enum class MissedExecutionBehavior { SKIP, RUN_IMMEDIATELY, RUN_ALL_MISSED }
 *    - val missedExecutionBehavior: MissedExecutionBehavior
 *    This is important when server is down during scheduled time.
 *
 * 2. Add execution tracking/history:
 *    - val lastExecutionTime: Instant?
 *    - val nextExecutionTime: Instant?
 *    - val executionCount: Long
 *
 * 3. Consider adding conditional execution:
 *    - suspend fun shouldExecute(): Boolean
 *    Allows tasks to check conditions before running.
 *
 * 4. Add lifecycle hooks for monitoring:
 *    - suspend fun onScheduled()
 *    - suspend fun onSkipped(reason: String)
 *
 * 5. Add support for exclusive execution to prevent overlapping runs:
 *    - val allowConcurrent: Boolean = false
 */
