# Tasks

A **task** is a named, serializable unit of work that runs outside the
request/response cycle.  You declare it once on a `ServerBuilder`, invoke it
from anywhere that has a `ServerRuntime` in context (an HTTP handler, a
WebSocket callback, a scheduled job), and the engine decides how to execute it.

Locally and in tests, `LocalEngine` runs the task body **synchronously** (inline,
before `launch()` returns), so effects are immediately observable.  In serverless
deployments the engine serializes the input and dispatches it via pub/sub for
background processing — your handler code is identical either way.

## Imports

All examples in this chapter use the following imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TasksSamples.kt#tasks-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.services.cache.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes
```

## Declaring and Invoking a Task

<!-- sample: com/lightningkite/lightningserver/guide/samples/TasksSamples.kt#task-server -->
```kotlin
@Serializable
data class WelcomeEmailInput(val address: String, val name: String)

object TaskServer : ServerBuilder() {

    val cache = setting("cache", Cache.Settings())

    // Declare a task on any path. The path acts as a unique identifier — it does not
    // correspond to an HTTP route. A Task executes its body when launched.
    //
    // Use Task { input: TYPE -> ... } (reified) when the input type is known at the call
    // site. The serializer is derived automatically. The handler runs with a ServerRuntime
    // in context, so it has access to settings, services, and other tasks.
    val sendWelcomeEmail = path.path("tasks").path("send-welcome-email") bind Task { input: WelcomeEmailInput ->
        println("Sending welcome email to ${input.address} (name=${input.name})")
        // Record the work in cache so tests can observe the effect.
        cache().set("last-welcome-email", input.address, 5.minutes)
    }

    // HTTP endpoint that launches the task.
    // launch() submits the task to the engine. In tests the engine executes it
    // synchronously (inline). In production, the engine may queue it via pub/sub
    // for background processing.
    val register = path.path("register").post bind HttpHandler { _ ->
        // In real code you'd parse the request body here and pass it to launch().
        // See the "task-test" region for how to call launch() directly in a test.
        sendWelcomeEmail.launch(WelcomeEmailInput("example@example.com", "Example"))
        HttpResponse.plainText("Registered!")
    }
}
```

Key points:

- The path passed to `bind Task { … }` is the task's **identity string**, used
  by the engine to route queued work.  It is not an HTTP route.
- `Task { input: TYPE -> … }` is the reified factory.  For polymorphic or
  contextual types, use `Task(serializer) { input -> … }` with an explicit
  `KSerializer<INPUT>`.
- The handler body runs with `ServerRuntime` as an implicit context receiver, so
  you can call `cache()`, `database()`, or any other service accessor.
- `launch(input)` is the one call to submit work.  It requires a `ServerRuntime`
  in context, so it can only be called from inside another handler, task, or
  scheduled job.

## Testing a Task

In `TestRunner` (used by the `SERVER.test { }` block), `task.launch(input)` runs
the task body **inline** — the body completes before `launch()` returns.  This
means you can assert effects on the very next line:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TasksSamples.kt#task-test -->
```kotlin
fun taskTest() = TaskServer.testBlocking(settings = {}) {
    // Launch the task directly. In TestRunner, Task.invoke calls executeInline —
    // the task body completes before launch() returns. The effect is immediately
    // visible in the next line.
    TaskServer.sendWelcomeEmail.launch(WelcomeEmailInput("alice@example.com", "Alice"))

    val logged = TaskServer.cache().get<String>("last-welcome-email")
    assertEquals("alice@example.com", logged)
}
```

## Delivery Semantics

The delivery guarantee depends on the engine:

- **`LocalEngine` / `TestRunner`**: synchronous, inline — no queue, no retries.
  The task body runs and completes before `launch()` returns (in `TestRunner`) or
  in a short-lived coroutine (in `LocalEngine`).
- **Serverless (AWS Lambda)**: the engine serializes the input and publishes it to
  the configured pub/sub channel.  Delivery is at-least-once.  Design task
  bodies to be **idempotent** — a retried invocation with the same input should
  produce the same outcome.

There is no built-in ordering guarantee between different task invocations.  If
order matters, express it in your task logic (e.g. sequence numbers in the
input, a database row that tracks progress).
