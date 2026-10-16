# Runtime Package

This package contains the core runtime system for Lightning Server applications: the process-wide `Engine`, the
per-execution `ServerRuntime`, and the `Execution` records that tie every piece of work to what caused it.

## Files

### Core Runtime Interfaces

- **[Engine.kt](Engine.kt)** - Primary interface for a running server instance, one per process. Holds what is shared
  across all work: the server definition, settings, serialization, the clock, WebSocket messaging, and task dispatch.
  An engine on its own is not performing any work, so it has no execution.

- **[EngineBase.kt](EngineBase.kt)** - Abstract base implementation providing common functionality for all
  engines including settings initialization, serialization setup, shared resources management, and startup and
  pre-deploy task orchestration.

- **[ServerRuntime.kt](ServerRuntime.kt)** - An engine performing one [Execution]. Only created by the framework when
  it starts an execution, never by user code. Declaring `context(ServerRuntime)` rather than `context(Engine)` is how a
  declaration says it does work that is attributed to an execution.

- **[Execution.kt](Execution.kt)** - What one execution is (an HTTP request, a WebSocket phase, a task, a schedule
  tick, a startup or pre-deploy task) and what caused it: its `parent`, its `rootExecution`, and its `origin`.
  Serializable, so parentage survives a task queue.

- **[ExecutionInterceptor.kt](ExecutionInterceptor.kt)** - Wraps every execution the server runs, of every kind — HTTP,
  each WebSocket phase, tasks, schedules, startup and pre-deploy — rather than one kind each, as the HTTP and WebSocket
  interceptors do. Installed on a `ServerBuilder`; first installed is outermost.

- **[Engine.ext.kt](Engine.ext.kt)** - Extension functions for convenient engine operations including
  setting access via `invoke()`, the clock, and location lookups for handlers and tasks.

- **[contextShortcuts.kt](contextShortcuts.kt)** - Runtime-scoped extensions: sending WebSocket topic messages and
  launching tasks, both of which need a `ServerRuntime` so the work they start has a parent.

### Running Executions

One file per kind of execution. Each kind has two sets of entry points: a root form for engines to call, marked
`@EngineApi`, and a `WithMetrics` form that runs the work as a child of the current execution.

- **[HttpExecution.kt](HttpExecution.kt)** - `Engine.handleRoot` for a request from a client, `ServerRuntime.handle`
  for one dispatched inside another (such as a `/meta/bulk` sub-request), and `HttpHandler.handleWithMetrics` for
  running a specific handler. Covers route resolution, HEAD translation from GET, trailing slash redirects, per-handler
  timeouts, and exception mapping.

- **[WebSocketExecution.kt](WebSocketExecution.kt)** - One entry point per lifecycle phase, in an `AsRoot` form and a
  `WithMetrics` form. Each phase is its own execution; `socketId` is what stays constant across a socket's phases.

- **[TaskExecution.kt](TaskExecution.kt)** - `executeInlineWithMetrics` for tasks and `executeWithMetrics` for
  schedules, startup and pre-deploy tasks.

- **[Instrumentation.kt](Instrumentation.kt)** - `instrument` for naming a child span.

### Utilities

- **[compression.kt](compression.kt)** - Internal GZIP utilities used by the opt-in `GzipInterceptor`.

### Testing

- **[test/TestRunner.kt](test/TestRunner.kt)** - An in-memory engine for unit tests, created by `ServerBuilder.test`
  and `ServerBuilder.testBlocking`. Supports custom clock injection and runs launched tasks inline.

- **[test/TestRunner.ext.kt](test/TestRunner.ext.kt)** - `.test()` methods on HTTP and WebSocket handlers, which run
  the call as a root execution through the server's interceptors, and `execute { }`, which runs a block as a
  `ServerRuntime` for direct database or service access.

## Key Concepts

### Engine, ServerRuntime and Execution

An `Engine` is the running server. A `ServerRuntime` is that engine while it handles one `Execution`. Every handler,
interceptor, task, and schedule body runs with a `ServerRuntime` in context, and anything it starts (a sub-request, a
task, a nested WebSocket phase) becomes a child execution. That chain is what lets logs, telemetry, and audit records
name what caused a piece of work, even across a task queue on a serverless engine.

Choose the context a declaration requires by what it does:

- `context(ServerRuntime)` for code that does work, such as using the database or other services, launching tasks, or
  sending topic messages. This is most user code, and handlers already run with one.
- `context(Engine)` for setup, and for helpers that only read shared state such as settings or serialization.

A `ServerRuntime` is also an `Engine`, so engine-level code can be called from anywhere.

```kotlin
context(_: ServerRuntime)
suspend fun notifyUser(userId: String) {
    val db = database()                      // Resolves a setting
    userTopic.send(userId, "Update!")        // Sends to WebSocket subscribers
    sendWelcomeEmail(WelcomeEmailInput(...)) // Launches a task as a child of this execution
}
```

### Telemetry

All executions are instrumented with OpenTelemetry when configured, providing distributed tracing for:

- HTTP requests (method, route, status, timing)
- WebSocket phases (connect, messages, disconnect)
- Task execution (type, location, success/failure)

### Automatic HTTP Features

The runtime automatically handles common HTTP concerns:

- **HEAD support**: Transforms GET requests and strips bodies
- **Trailing slash**: Redirects when the alternate form exists

Response compression is not automatic; install `GzipInterceptor` to enable it.

### Testing Support

`ServerBuilder.test` and `ServerBuilder.testBlocking` build the server on a `TestRunner`, which provides:

- Clock injection for time-based testing
- Inline execution of launched tasks
- WebSocket connection simulation

Inside the test block the only context is the `TestRunner`, which is an `Engine`, not a `ServerRuntime`. Handler
`.test()` helpers need nothing more. Anything else that needs a runtime, such as database access, goes inside
`execute { }`.

## Usage Examples

### Accessing Settings

```kotlin
object MyServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    val endpoint = path.get bind HttpHandler {
        val db = database()  // Gets configured database
        HttpResponse.plainText("OK")
    }
}
```

### Testing Endpoints

```kotlin
@Test
fun testEndpoint() = MyServer.testBlocking(
    settings = { database set Database.Settings("ram") }
) {
    val response = endpoint.test()
    assertEquals(HttpStatus.OK, response.status)

    val count = execute { itemTable().count() }  // Direct database access needs a ServerRuntime
    assertEquals(0, count)
}
```

### WebSocket Broadcasting

```kotlin
val userTopic = path.path("users").arg<String>("userId").topic(String.serializer())

// Send to all connections subscribed to a specific user
userTopic.send(path1 = "user123", value = "Update!")
```

### Task Execution

```kotlin
val processTask = path.path("tasks").path("process") bind Task { input: Data ->
    // Process data...
}

// Queue for background execution, as a child of the current execution
processTask(myData)
```

## See Also

- [Runtime Documentation](../../../../../../../../docs/runtime.md) - Detailed runtime guide
- [Testing Guide](../../../../../../../../docs-guide/guide/testing.md) - Testing best practices
- [Server Definition](../definition/index.md) - Server structure and configuration
