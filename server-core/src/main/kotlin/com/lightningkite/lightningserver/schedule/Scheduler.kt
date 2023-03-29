package com.lightningkite.lightningserver.schedule

import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId

object Scheduler {
    val schedules: MutableMap<String, ScheduledTask> = HashMap()
}

data class ScheduledTask(val name: String, val schedule: Schedule, val handler: suspend () -> Unit) {
    override fun toString(): String = "SCHEDULE $name"
}

sealed class Schedule {
    data class Frequency(val gap: Duration) : Schedule()
    data class Daily(val time: LocalTime, val zone: ZoneId = ZoneId.systemDefault()) : Schedule()
}