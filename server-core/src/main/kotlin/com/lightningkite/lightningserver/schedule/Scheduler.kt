package com.lightningkite.lightningserver.schedule

import kotlin.time.Duration
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

object Scheduler {
    val schedules: MutableMap<String, ScheduledTask> = HashMap()
}

data class ScheduledTask(val name: String, val schedule: Schedule, val handler: suspend () -> Unit) {
    override fun toString(): String = "SCHEDULE $name"
}

sealed class Schedule {
    data class Frequency(val gap: Duration) : Schedule()
    data class Daily(val time: LocalTime, val zone: TimeZone = TimeZone.currentSystemDefault()) : Schedule()
    data class Cron(val cron: CronPattern, val zone: TimeZone = TimeZone.currentSystemDefault()) : Schedule()
}