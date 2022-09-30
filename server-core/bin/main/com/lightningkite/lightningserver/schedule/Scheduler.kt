package com.lightningkite.lightningserver.schedule

import java.time.Duration
import java.time.LocalTime
import java.time.OffsetTime
import java.time.ZoneId
import java.util.TimeZone

object Scheduler {
    val schedules: MutableMap<String, ScheduledTask> = HashMap()
}

data class ScheduledTask(val name: String, val schedule: Schedule, val handler: suspend () -> Unit)

sealed class Schedule {
    data class Frequency(val gap: Duration): Schedule()
    data class Daily(val time: LocalTime, val zone: ZoneId = ZoneId.systemDefault()): Schedule()
}