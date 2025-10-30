# Runtime Package

This package contains the core runtime system for Lightning Server applications.

## Files

### Core Runtime Interfaces

- **[ServerRuntime.kt](ServerRuntime.kt)** - Primary interface for a running server instance. Defines the contract for server execution including settings access, serialization, task execution, and WebSocket messaging.

- **[ServerRuntimeBase.kt](ServerRuntimeBase.kt)** - Abstract base implementation providing common functionality for all runtimes including settings initialization, serialization setup, shared resources management, and startup task orchestration.

- **[ServerRuntime.ext.kt](ServerRuntime.ext.kt)** - Extension functions for convenient runtime operations including setting access via `invoke()`, WebSocket topic messaging, task execution, and location lookups for handlers and tasks.

### Request Handling

- **[implementationHelpers.kt](implementationHelpers.kt)** - Core HTTP request handling with automatic features including:
  - HEAD request translation from GET
  - Trailing slash redirect logic
  - GZIP compression negotiation and application
  - Exception handling and logging
  - Telemetry integration for all handler types (HTTP, WebSocket, tasks)

### Utilities

- **[compression.kt](compression.kt)** - Internal GZIP compression/decompression utilities used for HTTP response compression.

### Testing

- **[test/TestRunner.kt](test/TestRunner.kt)** - Test runtime providing synchronous, deterministic execution for unit tests. Supports custom clock injection, inline task execution, and WebSocket testing.

- **[test/TestRunner.ext.kt](test/TestRunner.ext.kt)** - Extension functions providing `.test()` methods on HTTP and WebSocket handlers for easy testing with automatic request construction and interceptor application.

## Key Concepts

### Context Receivers

This package makes extensive use of Kotlin context receivers to provide implicit access to the ServerRuntime. This allows clean code like:

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
