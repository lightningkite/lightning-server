package com.lightningkite.lightningserver.guide.samples

// region schedules-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.data.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.services.cache.Cache
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.datetime.*
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
// endregion schedules-imports

// region schedule-server
object ScheduleServer : ServerBuilder() {

    val cache = setting("cache", Cache.Settings())

    // Declare a scheduled task on any path. The path is the task's identity —
    // it does not correspond to an HTTP route.
    //
    // ScheduledTask(frequency) runs the body at a fixed interval.
    // The handler runs with a ServerRuntime in context, so services are available.
    val cleanup = path.path("schedules").path("cleanup") bind ScheduledTask(frequency = 1.hours) {
        // Delete expired sessions, purge stale cache entries, compact logs, etc.
        val count = 42  // illustrative; in production, query and delete from a database
        println("Cleanup ran: removed $count expired records")
        cache().set("cleanup:last-count", count, Int.serializer(), 2.hours)
    }
}
// endregion schedule-server

// region schedule-test
// Schedules are time-driven — the engine decides when to fire them.
// But the WORK inside a schedule is just a suspending function, and
// ScheduledTask.execute() can be called directly in a test with a
// ServerRuntime in context.
//
// This tests the work the schedule does, not the timing.
fun scheduleTest() = runBlocking {
    ScheduleServer.test(settings = {}) {
        // Call execute() directly — same as what the engine does when the timer fires.
        ScheduleServer.cleanup.execute()

        val count = ScheduleServer.cache().get("cleanup:last-count", Int.serializer())
        assertEquals(42, count)
    }
}
// endregion schedule-test
