# Schedules

A **scheduled task** is a recurring background job registered on your
`ServerBuilder`.  The engine fires it at the configured time — no external
cron daemon required.  Common uses: nightly cleanup, hourly metric snapshots,
daily digest emails, periodic data syncs.

## Imports

All examples in this chapter use the following imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/SchedulesSamples.kt#schedules-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.data.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.services.cache.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.datetime.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
```

`com.lightningkite.lightningserver.data.*` brings in `Schedule`,
`CronPattern`, `CronDays`, and `DayOfWeek.rangeTo`.
`kotlinx.datetime.*` brings in `LocalTime`, `TimeZone`, and `DayOfWeek`.

## Declaring a Scheduled Task

<!-- sample: com/lightningkite/lightningserver/guide/samples/SchedulesSamples.kt#schedule-server -->
```kotlin
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
        cache().set("cleanup:last-count", count, 2.hours)
    }
}
```

The path passed to `bind ScheduledTask(…)` is the task's **identity string**,
used by the engine for distributed locking (see below).  It is not an HTTP route.

The handler body runs with `ServerRuntime` as an implicit context receiver, so
`cache()`, `database()`, and `launch(task, input)` are all available.

### Schedule forms

`ScheduledTask` has four factory overloads:

| Form | When it runs |
|---|---|
| `ScheduledTask(frequency = 1.hours)` | Every hour |
| `ScheduledTask(frequency = 30.minutes)` | Every 30 minutes (minimum: 1 minute) |
| `ScheduledTask(timeOfDay = LocalTime(3, 0), timeZone = TimeZone.UTC)` | Daily at 03:00 UTC |
| `ScheduledTask(cron = CronPattern(…), timeZone = TimeZone.UTC)` | Any cron expression |

```kotlin
// Illustrative — these compile but the timing is engine-driven, not unit-testable.

// Every 30 minutes
val heartbeat = path.path("schedules").path("heartbeat") bind ScheduledTask(frequency = 30.minutes) {
    println("Heartbeat at ${kotlinx.datetime.Clock.System.now()}")
}

// Daily at 03:00 UTC — e.g. for a nightly digest email
val nightly = path.path("schedules").path("nightly") bind ScheduledTask(
    timeOfDay = LocalTime(3, 0),
    timeZone = TimeZone.UTC,
) {
    println("Sending nightly digest…")
}

// Weekdays at 09:00 using CronPattern
val weekdayMorning = path.path("schedules").path("weekday-morning") bind ScheduledTask(
    cron = CronPattern(
        minutes = listOf(0),
        hours = listOf(9),
        days = CronDays.DaysOfWeek(DayOfWeek.MONDAY..DayOfWeek.FRIDAY),
    ),
    timeZone = TimeZone.of("America/New_York"),
) {
    println("Good morning, weekday!")
}
```

## Testing a Scheduled Task

Scheduled tasks are time-driven — the engine polls a distributed clock and
fires the task when its next-run time has passed.  You cannot unit-test the
*timing*, but you can test the *work* the task does.

`ScheduledTask.execute()` is the same method the engine calls when the timer
fires.  Call it directly inside a `test {}` block to exercise the task body:

<!-- sample: com/lightningkite/lightningserver/guide/samples/SchedulesSamples.kt#schedule-test -->
```kotlin
// Schedules are time-driven — the engine decides when to fire them.
// But the WORK inside a schedule is just a suspending function, and
// ScheduledTask.execute() can be called directly in a test with a
// ServerRuntime in context.
//
// This tests the work the schedule does, not the timing.
fun scheduleTest() = ScheduleServer.testBlocking(settings = {}) {
    // Call execute() directly — same as what the engine does when the timer fires.
    ScheduleServer.cleanup.execute()

    val count = ScheduleServer.cache().get<Int>("cleanup:last-count")
    assertEquals(42, count)
}
```

## Distributed Execution and the Lock

In a multi-instance deployment (rolling deploys, serverless warm starts, multiple
Ktor/Netty pods) each instance runs its own schedule-poller loop.  The engine
prevents duplicate execution via a distributed cache lock:

1. Each instance polls its configured cache for a `"<path>-nextRun"` key.
2. When the next-run timestamp is reached, the instance attempts to set
   `"<path>-lock"` with `setIfNotExists`.  Only the first instance wins.
3. The winner runs the task body; the losers wait for the next poll cycle.
4. The lock is always released after the tick (even on cancellation), so a
   crashed instance does not block the next run.

The lock TTL defaults to 1 hour and is configurable via
`EngineReliabilitySettings.scheduleLockTtl` (set it on your engine's run-config
setting).  Because the lock is released as soon as the tick finishes (and on
graceful shutdown), the TTL only acts as a backstop after a hard crash — but a
task that runs longer than its lock TTL can have the lock expire before it
finishes, allowing another instance to start a concurrent tick.  Design schedule
bodies to complete well within their frequency interval, and design them to be
**idempotent** — a duplicate or retried tick should be safe.
