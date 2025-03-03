package com.lightningkite.lightningserver.schedule

import com.lightningkite.lightningserver.core.ServerContext
import com.lightningkite.lightningserver.core.ServerEntryPoint
import com.lightningkite.lightningserver.http.Request
import kotlin.time.Duration
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

object Scheduler {
    val schedules: MutableMap<String, ScheduledTask> = HashMap()
}

data class ScheduledTask(val name: String, val schedule: Schedule, val handler: suspend () -> Unit): ServerEntryPoint, ServerContext {
    override fun toString(): String = "SCHEDULE $name"
    override val request: Request? get() = null
    override val entryPoint: ServerEntryPoint get() = this
    override suspend fun logString(): String = toString()
}

sealed class Schedule {
    data class Frequency(val gap: Duration) : Schedule()
    data class Daily(val time: LocalTime, val zone: TimeZone = TimeZone.currentSystemDefault()) : Schedule()
    data class Cron(val cron: CronPattern, val zone: TimeZone = TimeZone.currentSystemDefault()) : Schedule()
}