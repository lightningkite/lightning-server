# Runtime Package

This package contains the core runtime system for Lightning Server applications.

## Files

### Core Runtime Interfaces

- **[Engine.kt](Engine.kt)** - Primary interface for a running server instance, one per process. Defines the contract
  for server execution including settings access, serialization, task execution, and WebSocket messaging.

- **[EngineBase.kt](EngineBase.kt)** - Abstract base implementation providing common functionality for all
  engines including settings initialization, serialization setup, shared resources management, and startup task
  orchestration.

- **[ServerRuntime.kt](ServerRuntime.kt)** - An engine running one execution, attributed to an [Initiator]. Declaring
  `context(ServerRuntime)` rather than `context(Engine)` is how a declaration says its work is attributable. Also holds
  the runtime-scoped extensions: WebSocket topic messaging and task launching.

- **[Initiator.kt](Initiator.kt)** - What started one execution, and what caused it to start. Serializable, so
  parentage survives a task queue.

- **[ExecutionRuntime.kt](ExecutionRuntime.kt)** - Mints a [ServerRuntime] from an engine plus an initiator.

- **[Engine.ext.kt](Engine.ext.kt)** - Extension functions for convenient engine operations including
  setting access via `invoke()`, the clock, and location lookups for handlers and tasks.

### Request Handling

- **[implementationHelpers.kt](implementationHelpers.kt)** - Core HTTP request handling with automatic features
  including:
    - HEAD request translation from GET
    - Trailing slash redirect logic
    - GZIP compression negotiation and application
    - Exception handling and logging
    - Telemetry integration for all handler types (HTTP, WebSocket, tasks)

### Utilities

- **[compression.kt](compression.kt)** - Internal GZIP compression/decompression utilities used for HTTP response
  compression.

### Testing

- **[test/TestRunner.kt](test/TestRunner.kt)** - Test runtime providing synchronous, deterministic execution for unit
  tests. Supports custom clock injection, inline task execution, and WebSocket testing.

- **[test/TestRunner.ext.kt](test/TestRunner.ext.kt)** - Extension functions providing `.test()` methods on HTTP and
  WebSocket handlers for easy testing with automatic request construction and interceptor application.

## Key Concepts

### Context Receivers

This package makes extensive use of Kotlin context parameters to provide implicit access to the engine or runtime.
Take a `ServerRuntime` when the work is done on someone's behalf and could be audited; take an `Engine` when it is not,
such as boot or settings resolution. This allows clean code like:

```kotlin
context(serverRuntime: ServerRuntime)
fun myFunction() {
    val db = database()  // Accesses setting from context
    myTopic.send("message")  // Sends to WebSocket subscribers
}
```

### Telemetry

All runtime operations are instrumented with OpenTelemetry when configured, providing distributed tracing for:

- HTTP requests (method, route, status, timing)
- WebSocket events (connect, disconnect, messages)
- Task execution (type, location, success/failure)

### Automatic HTTP Features

The runtime automatically handles common HTTP concerns:

- **HEAD support**: Transforms GET requests and strips bodies
- **Trailing slash**: Redirects when alternate form exists
- **GZIP compression**: Negotiates and applies compression intelligently

### Testing Support

The TestRunner provides a complete in-memory runtime for unit testing with:

- Synchronous execution for deterministic tests
- Clock injection for time-based testing
- WebSocket connection simulation
- Debug output for troubleshooting

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
fun testEndpoint() {
    MyServer.test(
        settings = { database.set(Database.JsonFile("test.json")) }
    ) {
        val response = endpoint.test()
        assertEquals(HttpStatus.OK, response.status)
    }
}
```

### WebSocket Broadcasting

```kotlin
val userTopic = path.path("users").arg<String>("userId").topic<String>()

// Send to all connections subscribed to a specific user
userTopic.send(path1 = "user123", value = "Update!")
```

### Task Execution

```kotlin
val processTask = task<Data> { input ->
    // Process data...
}

// Queue for background execution
processTask(myData)
```

## See Also

- [Runtime Documentation](../../../../../docs/runtime.md) - Detailed runtime guide
- [Testing Guide](../../../../../docs/testing.md) - Testing best practices
- [Server Definition](../definition/index.md) - Server structure and configuration
