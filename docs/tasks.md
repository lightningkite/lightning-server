# Scheduled and Asynchronous Tasks

You can define server tasks that run on a schedule, tasks that run asynchronously, and tasks that run when the server
starts or deploys. Every kind is bound to a path, which identifies it and must stay consistent across deploys; the path
is not an HTTP route.

## Scheduled Tasks

A `ScheduledTask` runs on a fixed interval, at a time of day, or on a cron pattern:

```kotlin
object Server : ServerBuilder() {
    val every15Minutes = path.path("schedules").path("every-15") bind ScheduledTask(frequency = 15.minutes) {
        println("This occurs every 15 minutes")
    }
    val daily = path.path("schedules").path("daily") bind ScheduledTask(
        timeOfDay = LocalTime(12, 0),
        timeZone = TimeZone.of("America/Denver"),
    ) {
        println("This occurs every day at noon, Denver time.")
    }
}
```

Each run is its own root execution.

## Async Tasks

A `Task` runs in the background with a serializable input:

```kotlin
object Server : ServerBuilder() {
    val testTask = path.path("tasks").path("test-task") bind Task { input: Int ->
        println("async task got $input")
    }
    val endpoint = path.path("test-task-start").get bind HttpHandler {
        testTask(42)
        HttpResponse.plainText("OK")
    }
}
```

To launch your task, call it like a function from inside a running execution, such as a handler. The task runs as a
child of the execution that launched it, so logs, telemetry, and audit records can trace it back to the request that
caused it, even when it runs in another process.

How a task runs depends on the engine: in a background coroutine on the local engines, as an asynchronous Lambda
invocation on AWS, and inline (before the launch returns) in tests.

Tasks can have any input type that is serializable.

## Startup and Pre-Deploy Tasks

A `StartupTask` runs on each instance before it serves, and a `PreDeployTask` runs once per deploy, before any instance
serves. Both can depend on others of their kind, and run in dependency order:

```kotlin
val warmCache = path.path("startup").path("warm-cache") bind StartupTask {
    // Runs on every instance at boot
}

val migrate = path.path("predeploy").path("migrate") bind PreDeployTask(
    dependencies = { listOf(userTable.preDeployTask) },
) {
    // Runs once per deploy, after the User table is prepared
}
```

Use a pre-deploy task for work that must not race between instances, such as preparing database tables or assigning
identifiers.

Note that if you are deploying to AWS Lambda, you need to be aware of time limits for the execution of your function.
