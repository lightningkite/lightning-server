# Server Runtime

The runtime system is the core execution environment for Lightning Server applications. It manages the lifecycle of your server, handles requests, executes tasks, and coordinates WebSocket subscriptions.

## Core Concepts

### ServerRuntime

`ServerRuntime` is the primary interface representing a running server instance. It provides:

- Access to the server definition (routes, handlers, settings)
- Serialization for both external APIs and internal storage
- Settings management
- Task execution
- WebSocket subscription broadcasting
- Telemetry integration

### Runtime Implementations

Lightning Server provides different runtime implementations for different deployment scenarios:

1. **SingleMachineEngine** - For traditional server deployments (Ktor, Netty, JDK HTTP)
2. **AWS Lambda** - For serverless deployments
3. **TestRunner** - For unit testing

## Using Settings

Within a ServerRuntime context, you can access configured settings using the invoke operator:

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

The recommended way to test Lightning Server applications is using the `.test()` extension:

```kotlin
class MyServerTest {
    @Test
    fun testEndpoint() {
        MyServer.test(
            settings = {
                // Configure test settings
                database.set(Database.JsonFile("test.json"))
            }
        ) {
            // Test your endpoints
            val response = myEndpoint.test()
            assertEquals(HttpStatus.OK, response.status)
            assertEquals("Hello!", response.body?.text())
        }
    }
}
```

### Testing WebSockets

WebSocket handlers return a `TestWebSocket` instance:

```kotlin
MyServer.test(settings = {}) {
    val ws = myWebSocketHandler.test()

    // Send a message to the server
    ws.send(WebSocketFrame.Text("Hello"))

    // Capture messages from the server
    ws.onMessageSent = { frame ->
        println("Server sent: $frame")
    }

    // Inspect connection state
    println("Current state: ${ws.currentState}")

    // Close the connection
    ws.close()
}
```

## Task Execution

Tasks can be queued for background execution:

```kotlin
val myTask = task<String> { input ->
    println("Processing: $input")
}

// Within a ServerRuntime context:
myTask("some data") // Queues task for execution
```

The exact execution behavior depends on the runtime:
- **SingleMachineEngine**: Executes in GlobalScope (fire-and-forget)
- **TestRunner**: Executes inline (synchronous for testing)
- **AWS Lambda**: Queues to SQS or similar service

## WebSocket Subscriptions

Send messages to all subscribers of a topic:

```kotlin
val myTopic = topic<PathSpec0, String>()

// Within a ServerRuntime context:
myTopic.send("Hello all subscribers!")
```

For topics with path parameters:

```kotlin
val userTopic = path.path("users").arg<String>("userId").topic<String>()

// Send to all connections subscribed to a specific user
userTopic.send(
    path1 = "user123",
    value = "New notification!"
)
```

## Compression

The runtime automatically compresses HTTP responses when:
- Client sends `Accept-Encoding: gzip` header
- Response body is at least 256 bytes
- Content type is compressible (not already compressed like images/video)

For payloads between 256-1024 bytes, compression is only applied if it reduces size.

## Telemetry

When telemetry is configured, the runtime automatically:
- Traces HTTP requests with method, route, status code
- Traces WebSocket events (connect, message, disconnect)
- Traces task execution
- Records exceptions with context

Configure telemetry in your settings:

```kotlin
val telemetry = setting("telemetry", OpenTelemetry.Settings())
```

## Clock for Testing

The runtime provides a `now()` function for time-based operations. In tests, you can inject a custom clock:

```kotlin
@Test
fun testTimeDependent() {
    var currentTime = Clock.System.now()

    @Suppress("DEPRECATION")
    val runner = TestRunner(MyServer, clockGet = {
        object : Clock {
            override fun now() = currentTime
        }
    })

    // Test time-dependent behavior
    currentTime += 1.hours
    // ...
}
```

## Best Practices

1. **Use context receivers**: The runtime system heavily uses Kotlin context receivers for clean, implicit access to the runtime
2. **Test with mock services**: Use JSON file databases and test implementations for deterministic tests
3. **Access settings via invoke**: Use `setting()` not direct property access to get resolved values
4. **Store endpoint references**: Always store endpoints in val constants for testing and internal calls
5. **Handle tasks asynchronously**: Don't assume tasks complete immediately in production code

## See Also

- [Testing Guide](testing.md) - Comprehensive testing documentation
- [Settings](settings.md) - Configuring your server
- [WebSockets](websockets.md) - WebSocket implementation guide
- [Tasks and Schedules](tasks.md) - Background task execution
