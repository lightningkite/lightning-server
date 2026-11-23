# Schedules

A `ScheduledTask` runs on a fixed interval, at a time of day, or on a cron pattern: nightly cleanup, hourly syncs,
digest emails.  The engine fires it; there's no separate cron daemon, and only one instance runs each tick.

<!-- sample: com/lightningkite/lightningserver/guide/samples/SchedulesSamples.kt#schedules-server -->
```kotlin
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
```

As with [tasks](tasks.md), the bound path is the schedule's identity (it names its lock and its state), not an HTTP
route.  Each tick runs as its own execution with a `ServerRuntime` in context.  `nightlyDigest` shows the usual shape
for big jobs: the tick stays short and fans the work out to a task.

Imports used in this chapter:

<!-- sample: com/lightningkite/lightningserver/guide/samples/SchedulesSamples.kt#schedules-imports -->
```kotlin
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
```

`CronPattern`, `CronDays`, `Schedule` and the `DayOfWeek` range operator come from
`com.lightningkite.lightningserver.data`; `LocalTime`, `TimeZone` and `DayOfWeek` from `kotlinx.datetime`.

## Schedule forms

| Factory | Runs |
|---|---|
| `ScheduledTask(frequency = 15.minutes)` | Every interval.  At least one minute. |
| `ScheduledTask(timeOfDay = LocalTime(3, 0), timeZone = ...)` | Daily at that local time. |
| `ScheduledTask(cron = CronPattern(...), timeZone = ...)` | Whenever the pattern matches. |
| `ScheduledTask(schedule = Schedule.Daily(...))` | Takes a `Schedule` value directly. |

Each takes `timeout` (default 5 minutes).

`CronPattern` takes `minutes`, `hours`, `days` and `months`; anything left out means "every".  `days` is
`CronDays.All`, `CronDays.DaysOfWeek(...)`, or `CronDays.DaysOfMonth(...)`.  Days of the month can be
`CronDayOfMonth.Last` or `CronDayOfMonth.NearestWeekday(15)`; days of the week can be
`CronDayOfWeek(DayOfWeek.FRIDAY, last = true)` (last Friday) or `CronDayOfWeek(DayOfWeek.MONDAY, recurrence = 2)`
(second Monday).

## Testing a schedule

The timing belongs to the engine, but the work can run on demand:

<!-- sample: com/lightningkite/lightningserver/guide/samples/SchedulesSamples.kt#schedules-test -->
```kotlin
@OptIn(EngineApi::class)
fun schedulesRunOnDemandTest() = SchedulesServer.testBlocking(settings = {}) {
    SchedulesServer.hourlyCleanup.executeWithMetrics()
    assertEquals(1, SchedulesServer.cache().get<Int>("cleanup:runs"))

    SchedulesServer.nightlyDigest.executeWithMetrics()
    assertEquals("sent", SchedulesServer.cache().get<String>("digest:bob@example.com"))
}
```

`executeWithMetrics()` is what the engine calls when a tick is due: the body runs as a new execution, through the
server's execution interceptors.  It is engine API, hence the opt-in.  Tasks the tick launches run inline, as
everywhere in tests.

## How ticks run

**Local engines (Netty, Ktor, JDK).**  Every instance runs a loop per schedule, coordinated through the engine's cache
(the `engine-cache` setting):

1. The next run time is stored under `<path>-nextRun`.
2. When it's due, an instance takes `<path>-lock` with `setIfNotExists`.  Only the winner runs the tick; the others
   wait for the next one.
3. After each tick, successful or not, the next run time moves forward.  The lock is released when the tick ends, even if it
   fails or the server shuts down.

So for more than one instance, `engine-cache` must be a shared cache (Redis, DynamoDB, ...).  With the default
in-memory cache, every instance has its own lock and every instance runs every tick.  The stored next run time
survives restarts as long as the cache does: if a run came due while every instance was down, the first instance back
runs it once on boot.  A `frequency` schedule with no stored state first runs one interval after boot.

A tick that throws is reported and waits for its next scheduled run; it isn't retried sooner.  Retry inside the tick
if a failure should be retried before then.

The lock expires after `scheduleLockTtl` (default 1 hour), set in the engine's run-config setting
(`nettyRunConfig`, `ktorRunConfig` or `jdkRunConfig`, under `reliability`).  The TTL only matters after a hard crash,
but a tick that runs longer than it can be started a second time by another instance.  On shutdown, a running tick is
cancelled after `shutdownDrainTimeout`.  Design ticks to be short and safe to repeat.

**AWS Lambda.**  The generated Terraform creates an EventBridge rule per schedule, which invokes the function with the
schedule's path.  EventBridge works in UTC: a `timeOfDay` is converted to UTC once, so it does not follow daylight
saving time, and a cron schedule's `timeZone` is not applied at all.  Use `TimeZone.UTC` for schedules you deploy to
Lambda.  See [AWS deployment](aws-deployment.md).

## Next

- [Tasks](tasks.md): background work launched on demand, and startup/pre-deploy tasks.
- [Caching](caching.md): choosing the shared cache that coordinates instances.
- [Engines](engines.md): run-config settings, including `reliability`.
