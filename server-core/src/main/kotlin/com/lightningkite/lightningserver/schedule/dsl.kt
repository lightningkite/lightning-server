package com.lightningkite.lightningserver.schedule

import com.lightningkite.lightningserver.core.LightningServerDsl
import java.time.Duration
import java.time.LocalTime

@LightningServerDsl
fun schedule(name: String, frequency: Duration, action: suspend () -> Unit): ScheduledTask {
    val task = ScheduledTask(name, Schedule.Frequency(frequency), action)
    Scheduler.schedules[name] = task
    return task
}

@LightningServerDsl
fun schedule(name: String, time: LocalTime, action: suspend () -> Unit): ScheduledTask {
    val task = ScheduledTask(name, Schedule.Daily(time), action)
    Scheduler.schedules[name] = task
    return task
}