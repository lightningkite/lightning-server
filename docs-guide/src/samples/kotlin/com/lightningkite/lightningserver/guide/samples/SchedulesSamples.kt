package com.lightningkite.lightningserver.guide.samples

// region schedules-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.data.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.services.cache.*
import kotlinx.datetime.*
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
// endregion schedules-imports

// region schedules-server
object SchedulesServer : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())

    val hourlyCleanup = path.path("schedules").path("hourly-cleanup") bind ScheduledTask(frequency = 1.hours) {
        cache().add("cleanup:runs", 1)
    }

    val sendDigest = path.path("tasks").path("send-digest") bind Task { address: String ->
        cache().set("digest:$address", "sent")
    }

    val nightlyDigest = path.path("schedules").path("nightly-digest") bind ScheduledTask(
        timeOfDay = LocalTime(3, 0),
        timeZone = TimeZone.of("America/Denver"),
    ) {
        for (address in listOf("alice@example.com", "bob@example.com")) sendDigest.launch(address)
    }

    val weekdayStandup = path.path("schedules").path("weekday-standup") bind ScheduledTask(
        cron = CronPattern(
            minutes = listOf(0),
            hours = listOf(9),
            days = CronDays.DaysOfWeek(DayOfWeek.MONDAY..DayOfWeek.FRIDAY),
        ),
        timeZone = TimeZone.UTC,
    ) {
        println("Standup in 15 minutes")
    }
}
// endregion schedules-server

// region schedules-test
@OptIn(EngineApi::class)
fun schedulesRunOnDemandTest() = SchedulesServer.testBlocking(settings = {}) {
    SchedulesServer.hourlyCleanup.executeWithMetrics()
    assertEquals(1, SchedulesServer.cache().get<Int>("cleanup:runs"))

    SchedulesServer.nightlyDigest.executeWithMetrics()
    assertEquals("sent", SchedulesServer.cache().get<String>("digest:bob@example.com"))
}
// endregion schedules-test
