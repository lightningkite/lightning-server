# Tasks

A task is work that runs outside the request that asked for it: sending an email, resizing an upload, calling a slow
API.  Declare it once on a `ServerBuilder` and launch it from any handler; the engine decides where it runs.  The same
chapter covers startup and pre-deploy tasks, which run when the server boots or deploys.

<!-- sample: com/lightningkite/lightningserver/guide/samples/TasksSamples.kt#tasks-server -->
```kotlin
@Serializable
data class WelcomeEmail(val address: String, val name: String)

object TasksServer : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())

    val sendWelcomeEmail = path.path("tasks").path("send-welcome-email") bind Task { input: WelcomeEmail ->
        // Stand-in for sending the email.
        cache().set("welcomed:${input.address}", input.name)
    }

    val register = path.path("register").arg<String>("email").post bind HttpHandler { request ->
        sendWelcomeEmail.launch(WelcomeEmail(address = request.arg1, name = "New user"))
        HttpResponse.plainText("Registered")
    }
}
```

The path a task is bound to is its identity, not an HTTP route.  Keep it stable across deploys: a queued task is looked
up by that path when it runs.  The input can be any serializable type, and the body runs with a `ServerRuntime` in
context, so settings and services (`cache()`, `database()`, other tasks) are available.

`sendWelcomeEmail.launch(input)` and `sendWelcomeEmail(input)` are the same call.  Both need a running execution (a
handler, a socket callback, another task, a schedule), and the launched task runs as its child, so logs and traces lead
back to the request that caused it.

Imports used in this chapter:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TasksSamples.kt#tasks-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.*
import com.lightningkite.services.database.*
import kotlinx.serialization.Serializable
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
```

## Where tasks run

| Engine | What `launch` does |
|---|---|
| Tests (`testBlocking`) | Runs the task inline; it finishes before `launch` returns. |
| Local engines (Netty, Ktor, JDK) | Starts it in a background coroutine in the same process.  Nothing is persisted: a restart drops tasks in flight. |
| AWS Lambda | Invokes the function asynchronously with the serialized input, so the task runs in its own invocation. |

A task that throws is reported like any other failed execution; `launch` does not see the exception (except in tests,
where it runs inline).  On Lambda, failed asynchronous invocations can be retried, so make task bodies safe to run
twice.  There is no ordering between tasks: if order matters, carry it in the input or in the database.

## Testing a task

<!-- sample: com/lightningkite/lightningserver/guide/samples/TasksSamples.kt#tasks-test -->
```kotlin
fun tasksLaunchTest() = TasksServer.testBlocking(settings = {}) {
    TasksServer.register.test("alice@example.com")
    assertEquals("New user", TasksServer.cache().get<String>("welcomed:alice@example.com"))

    // launch() needs a running execution; execute { } gives the test one.
    execute { TasksServer.sendWelcomeEmail.launch(WelcomeEmail("bob@example.com", "Bob")) }
    assertEquals("Bob", TasksServer.cache().get<String>("welcomed:bob@example.com"))
}
```

Because tasks run inline in tests, their effects are visible on the next line.

## Timeouts and explicit serializers

<!-- sample: com/lightningkite/lightningserver/guide/samples/TasksSamples.kt#tasks-timeout -->
```kotlin
val monthlyReport = Task(timeout = 30.minutes) { month: String ->
    println("Building the report for $month")
}
```

The default timeout is 5 minutes.  On Lambda the function's own time limit also applies, whichever is shorter.

For polymorphic or contextual input types, pass the serializer yourself: `Task(MyInput.serializer()) { input -> ... }`.

## Startup and pre-deploy tasks

<!-- sample: com/lightningkite/lightningserver/guide/samples/TasksSamples.kt#tasks-lifecycle -->
```kotlin
@Serializable
data class VisitCounter(override val _id: String, val value: Int) : HasId<String>

object TasksLifecycleServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val counters = database.registerTable<VisitCounter>("VisitCounter")

    val seedCounters = path.path("predeploy").path("seed-counters") bind PreDeployTask(
        dependencies = { listOf(counters.preDeployTask) },
    ) {
        if (counters().get("visits") == null) counters().insertOne(VisitCounter("visits", 0))
    }

    val logBoot = path.path("startup").path("log-boot") bind StartupTask {
        println("Instance starting")
    }
}
```

| | `StartupTask` | `PreDeployTask` |
|---|---|---|
| Runs | On every instance, at boot, before it serves | Once per deploy, before the new version serves |
| Good for | Per-process setup: warming a local cache, logging the build | Work that must not race between instances: migrations, backfills, preparing tables |
| On failure | The instance fails to start | The deploy is aborted; the previous version keeps serving |
| `dependencies` | `Collection<StartupTask>` | `() -> Collection<PreDeployTask>`, a lambda so it can name tasks declared later or in other modules |

Both run in dependency order, with independent tasks running concurrently.  A dependency cycle fails `build()`.
`registerTable` creates a pre-deploy task (`counters.preDeployTask`) that creates the table's collection and indexes;
depend on it before touching the table.

Every pre-deploy task runs on every deploy; nothing records that it ran before.  Write them so that running again
changes nothing, like `seedCounters` above.  For work that must happen exactly once ever, `predeployOnce(database) { }`
and `startupOnce(database) { }` (in `typed`) record completion in the database under the task's path.  A failed run is
recorded and rethrown, so it fails the deploy (or startup); the next run tries again.

On the local engines, `runPreDeploy()` runs the pre-deploy tasks without serving (the body of a `predeploy`
command), and `runPreDeployTasksBlocking()` runs them just before `start()` in a dev process; see
[Running](running.md).  On AWS, the
generated Terraform invokes the new function version to run them before switching traffic to it; see
[AWS deployment](aws-deployment.md).

Tests don't run either kind automatically:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TasksSamples.kt#tasks-lifecycle-test -->
```kotlin
fun tasksLifecycleTest() = TasksLifecycleServer.testBlocking(settings = {}) {
    executePreDeployTasks()
    executePreDeployTasks()  // every deploy runs them again, so they must be safe to repeat
    assertEquals(0, TasksLifecycleServer.counters().get("visits")?.value)

    executeStartupTasks()
}
```

## Next

- [Schedules](schedules.md): run work on an interval, a time of day, or a cron pattern.
- [Caching](caching.md): deduplicating and locking work across instances.
- [Observability](observability.md): following a launched task back to its request.
