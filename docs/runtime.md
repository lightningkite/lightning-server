# Server Runtime

The runtime system is the core execution environment for Lightning Server applications. It manages the lifecycle of your
server, handles requests, executes tasks, and coordinates WebSocket subscriptions.

## Core Concepts

### Engine

An `Engine` is the running server, one per process. It provides:

- Access to the server definition (routes, handlers, settings)
- Serialization for both external APIs and internal storage
- Settings management
- Task dispatch
- WebSocket subscription broadcasting
- Telemetry integration and the clock

An engine on its own is not doing any particular work.

### ServerRuntime and Execution

A `ServerRuntime` is an engine while it handles one `Execution`: an HTTP request, one phase of a WebSocket, a task, a
schedule tick, or a startup or pre-deploy task. The framework creates one each time it starts an execution, and every
handler, interceptor, and task body runs with it in context.

Each execution records what caused it:

- `id` identifies the execution. It is a version 7 UUID, so it carries a timestamp.
- `parent` is the execution that started it, or `null` for one nothing started, such as a request from a client.
- `rootExecution` is the head of the chain.
- `origin` is the execution the work is attributed to: the request or socket for anything they start, including tasks
  queued from them.

A sub-request inside `/meta/bulk`, a task launched from a handler, or a virtual socket inside a multiplexed one is a
child of whatever started it. Executions are serializable, so a task keeps its parent even when it runs in a different
process.

Choose the context a function requires by what it does:

- `context(ServerRuntime)` for code that does work, such as using the database or other services, launching tasks, or
  sending topic messages.
- `context(Engine)` for setup, and for helpers that only read shared state such as settings or serialization.

A `ServerRuntime` is also an `Engine`, so engine-level code can be called from anywhere.

### Engine Implementations

Lightning Server provides different engines for different deployment scenarios:

1. **LocalEngine** - The base for single-process deployments, extended by `KtorEngine`, `NettyEngine`, and `JdkEngine`
2. **AwsAdapter** - For AWS Lambda deployments
3. **TestRunner** - For unit testing

## Using Settings

Within an engine or runtime context, you can access configured settings using the invoke operator:

```kotlin
object MyServer : ServerBuilder() {
    val database = setting("database", Database.Settings())

    val myEndpoint = path.get bind HttpHandler {
        val db = database() // Retrieves configured database
        // Use the database...
        HttpResponse.plainText("Hello!")
    }
}
```

## Testing Your Server

The recommended way to test Lightning Server applications is `testBlocking`, which builds the server on a `TestRunner`
and runs a suspending test body:

```kotlin
class MyServerTest {
    @Test
    fun testEndpoint() = MyServer.testBlocking(
        settings = { database set Database.Settings("ram") }
    ) {
        val response = myEndpoint.test()
        assertEquals(HttpStatus.OK, response.status)
        assertEquals("Hello!", response.body?.text())
    }
}
```

The `TestRunner` is an `Engine`, not a `ServerRuntime`. Handler `.test()` helpers run the call as a real root
execution, so they need nothing more. Anything else that needs a runtime, such as database access, goes inside
`execute { }`:

```kotlin
val count = execute { itemTable().count() }
```

### Testing WebSockets

WebSocket handlers return a `TestWebSocket` instance:

```kotlin
MyServer.testBlocking(settings = {}) {
    val ws = myWebSocketHandler.test()

    // Capture messages from the server
    ws.onMessageSent = { frame ->
        println("Server sent: $frame")
    }

    // Send a message to the server
    ws.send(WebSocketFrame("Hello"))

    // Inspect connection state
    println("Current state: ${ws.currentState}")

    // Close the connection
    ws.close()
}
```

## Task Execution

Tasks can be queued for background execution:

```kotlin
val myTask = path.path("tasks").path("my-task") bind Task { input: String ->
    println("Processing: $input")
}

// Within a ServerRuntime context:
myTask("some data") // Queues task for execution
```

A launched task is a child execution of whatever launched it. The exact execution behavior depends on the engine:

- **LocalEngine** and the engines built on it: run the task in a background coroutine in the same process
- **TestRunner**: runs the task inline, before the launch returns
- **AwsAdapter**: invokes the Lambda function asynchronously with the task, its input, and the launching execution

## WebSocket Subscriptions

Send messages to all subscribers of a topic:

```kotlin
val myTopic = path.path("announcements").topic(String.serializer())

// Within a ServerRuntime context:
myTopic.send("Hello all subscribers!")
```

For topics with path parameters:

```kotlin
val userTopic = path.path("users").arg<String>("userId").topic(String.serializer())

// Send to all connections subscribed to a specific user
userTopic.send(
    path1 = "user123",
    value = "New notification!"
)
```

## Interceptors

Three interceptor chains can be installed on a `ServerBuilder`:

- `HttpInterceptor` wraps each HTTP request, including sub-requests.
- `WebSocketInterceptor` wraps WebSocket handlers.
- `ExecutionInterceptor` wraps every execution of every kind, including tasks, schedules, startup and pre-deploy tasks.

Each runs for nested executions as well as root ones. An interceptor whose concern belongs to the connection rather
than to the work can check `runtime.execution.isRoot()`.

## Compression

Response compression is opt-in. Install `GzipInterceptor` to compress HTTP responses when:

- Client sends `Accept-Encoding: gzip` header
- Response body is at least 256 bytes
- Content type is compressible (not already compressed like images/video)

For payloads between 256-1024 bytes, compression is only applied if it reduces size.

## Telemetry

When telemetry is configured, the runtime automatically:

- Traces HTTP requests with method, route, status code
- Traces WebSocket phases (connect, message, disconnect)
- Traces task execution
- Records exceptions with context

Telemetry is configured through the built-in `telemetry` setting in `settings.json`. It is disabled by default.

## Clock for Testing

The runtime provides a `now()` function for time-based operations. In tests, you can inject a custom clock:

```kotlin
@Test
fun testTimeDependent() {
    var currentTime = Clock.System.now()

    MyServer.testBlocking(settings = {}, clock = { object : Clock { override fun now() = currentTime } }) {
        // Test time-dependent behavior
        currentTime += 1.hours
        // ...
    }
}
```

## Best Practices

1. **Use context parameters**: The runtime system uses Kotlin context parameters for implicit access to the engine or
   runtime
2. **Test with mock services**: Use the in-memory `ram` database and cache for deterministic tests
3. **Access settings via invoke**: Call `setting()` to get the resolved value rather than reading the setting directly
4. **Store endpoint references**: Always store endpoints in val constants for testing and internal calls
5. **Handle tasks asynchronously**: Don't assume tasks complete immediately in production code

## See Also

- [Testing Guide](../docs-guide/guide/testing.md) - Comprehensive testing documentation
- [Settings](settings.md) - Configuring your server
- [WebSockets](websockets.md) - WebSocket implementation guide
- [Tasks and Schedules](tasks.md) - Background task execution
