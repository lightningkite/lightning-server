package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.data.Schedule
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

public interface ScheduledTask {
    public val schedule: Schedule
    public val timeout: Duration get() = 30.seconds
    context(server: ServerRuntime)
    public suspend fun execute()
}

public fun ScheduledTask(
    schedule: Schedule,
    timeout: Duration = 5.minutes,
    handler: suspend ServerRuntime.() -> Unit
): ScheduledTask =
    object : ScheduledTask {
        override val schedule: Schedule = schedule
        override val timeout: Duration = timeout
        context(server: ServerRuntime)
        override suspend fun execute() {
            handler(server)
        }
    }

public fun schedule(
    schedule: Schedule,
    timeout: Duration = 5.minutes,
    handler: suspend ServerRuntime.() -> Unit
): ScheduledTask = ScheduledTask(schedule, timeout, handler)