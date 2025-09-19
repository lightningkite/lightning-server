package com.lightningkite.lightningserver.data

import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration

public sealed class Schedule {
    public data class Frequency(val gap: Duration) : Schedule()
    public data class Daily(val time: LocalTime, val zone: TimeZone = TimeZone.currentSystemDefault()) : Schedule()
    public data class Cron(val cron: CronPattern, val zone: TimeZone = TimeZone.currentSystemDefault()) : Schedule()
}