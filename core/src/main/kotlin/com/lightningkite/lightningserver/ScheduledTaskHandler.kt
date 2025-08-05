package com.lightningkite.lightningserver

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

public interface ScheduledTaskHandler {
    public val schedule: Schedule
    public val timeout: Duration get() = 30.seconds
    public suspend fun execute(serverRunning: ServerRunning)
}
public fun ServerDefinitionBuilder<*>.scheduleHandler(
    schedule: Schedule,
    timeout: Duration = 5.minutes,
    handler: suspend ServerRunning.() -> Unit
): ScheduledTaskHandler =
    object : ScheduledTaskHandler {
        override val schedule: Schedule = schedule
        override val timeout: Duration = timeout
        override suspend fun execute(serverRunning: ServerRunning) {
            handler(serverRunning)
        }
    }