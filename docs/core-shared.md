# Core Shared Module

The `core-shared` module contains fundamental data structures and types that are shared between client and server in Lightning Server applications. This is a Kotlin Multiplatform module, meaning these types can be used in both JVM server code and client applications (including JavaScript, iOS, Android, etc.).

## Overview

The core-shared module provides:
- **LSError**: A standardized error response format for all API endpoints
- **HttpMethod**: A type-safe representation of HTTP methods
- **MultiplexMessage**: Support for multiplexed WebSocket communications

These types are designed to be serializable with KotlinX Serialization, making them suitable for network communication between client and server.

## LSError

`LSError` provides a consistent error response format across all Lightning Server endpoints. This standardization makes it easy for clients to handle errors uniformly.

### Structure

```kotlin
data class LSError(
    val http: Int,              // HTTP status code (e.g., 404, 500)
    val detail: String = "",    // Machine-readable error code
    val message: String = "",   // Human-readable error message
    val data: String = "",      // Additional structured data as JSON
    val stackTrace: String? = null  // Optional stack trace (debug only)
)
```

### Usage

**Creating standard errors:**

```kotlin
// Not found error
val notFound = LSError(
    http = 404,
    detail = "not-found",
    message = "The requested resource was not found"
)

// Validation error with additional data
val validationError = LSError(
    http = 400,
    detail = "validation-failed",
    message = "Invalid input provided",
    data = """{"field": "email", "reason": "invalid format"}"""
)

// Internal server error with stack trace (development)
val internalError = LSError(
    http = 500,
    detail = "internal-error",
    message = "An unexpected error occurred",
    stackTrace = exception.stackTraceToString()
)
```

**In typed endpoints:**

```kotlin
val myEndpoint = path.path("resource").get.api(
    summary = "Get a resource",
    errorCases = listOf(
        LSError(http = 404, detail = "not-found", message = "Resource not found"),
        LSError(http = 401, detail = "unauthorized", message = "Authentication required")
    ),
    implementation = { input ->
        // Your implementation
    }
)
```

### Best Practices

1. **Use consistent detail codes**: Establish a standard set of detail codes across your API (e.g., "not-found", "validation-failed", "unauthorized")

2. **Message for humans, detail for code**: The `message` field should be suitable for displaying to end users, while `detail` is for programmatic error handling

3. **Only include stack traces in development**: Never expose stack traces in production environments as they may leak sensitive information

4. **Use the data field for structured errors**: When you need to return structured error information (like validation errors for multiple fields), encode it as JSON in the `data` field

## HttpMethod

`HttpMethod` is a type-safe, zero-overhead value class representing HTTP methods. It prevents typos and provides compile-time safety while maintaining performance.

### Standard Methods

The following HTTP methods are available as companion object constants:

```kotlin
HttpMethod.GET      // Retrieve a resource
HttpMethod.POST     // Create a resource or trigger an action
HttpMethod.PUT      // Replace an entire resource
HttpMethod.PATCH    // Partially update a resource
HttpMethod.DELETE   // Remove a resource
HttpMethod.OPTIONS  // Describe communication options
HttpMethod.HEAD     // Get headers only (no body)
HttpMethod.WEBSOCKET // WebSocket connections (pseudo-method)
```

### Usage

**Defining endpoints:**

```kotlin
// GET endpoint
val getUser = path.path("users").arg<String>("id").get bind HttpHandler { request ->
    // Handle GET request
}

// POST endpoint
val createUser = path.path("users").post bind HttpHandler { request ->
    // Handle POST request
}
```

**Checking methods:**

```kotlin
when (request.method) {
    HttpMethod.GET -> handleGet()
    HttpMethod.POST -> handlePost()
    HttpMethod.PUT, HttpMethod.PATCH -> handleUpdate()
    HttpMethod.DELETE -> handleDelete()
    else -> HttpResponse.plainText("Method not allowed", status = 405)
}
```

### Notes

- `HttpMethod` is a value class, so it has zero runtime overhead on the JVM
- The `WEBSOCKET` constant is a pseudo-method used internally for WebSocket upgrade handling, not a standard HTTP method
- Custom HTTP methods can be created by constructing new instances, though this is rarely needed

## MultiplexMessage

`MultiplexMessage` enables multiplexing multiple logical communication channels over a single WebSocket connection. This is useful for applications that need multiple concurrent streams of data without opening multiple WebSocket connections.

### Structure

```kotlin
data class MultiplexMessage(
    val channel: String,                            // Channel identifier
    val path: String? = null,                       // Path for channel setup
    val queryParams: Map<String, List<String>>? = null,  // Query params for setup
    val start: Boolean = false,                     // Initiates a new channel
    val end: Boolean = false,                       // Terminates the channel
    val data: String? = null,                       // Message payload
    val error: String? = null                       // Error message
)
```

### Message Types

**Start message** - Initiates a new channel:

```kotlin
MultiplexMessage(
    channel = "notifications",
    path = "/api/notifications/stream",
    queryParams = mapOf("userId" to listOf("123")),
    start = true
)
```

**Data message** - Sends data on an existing channel:

```kotlin
MultiplexMessage(
    channel = "notifications",
    data = """{"type": "new_message", "content": "Hello!"}"""
)
```

**Error message** - Indicates an error on the channel:

```kotlin
MultiplexMessage(
    channel = "notifications",
    error = "Authentication failed"
)
```

**End message** - Gracefully closes the channel:

```kotlin
MultiplexMessage(
    channel = "notifications",
    end = true
)
```

### Usage Pattern

**Client side:**

```kotlin
// 1. Start a channel
websocket.send(MultiplexMessage(
    channel = "chat",
    path = "/api/chat/room/general",
    start = true
))

// 2. Send messages on the channel
websocket.send(MultiplexMessage(
    channel = "chat",
    data = """{"text": "Hello everyone!"}"""
))

// 3. Close the channel when done
websocket.send(MultiplexMessage(
    channel = "chat",
    end = true
))
```

**Server side:**

```kotlin
when {
    message.start -> {
        // Initialize channel with path and queryParams
        startChannel(message.channel, message.path, message.queryParams)
    }
    message.data != null -> {
        // Process incoming data
        handleData(message.channel, message.data)
    }
    message.error != null -> {
        // Handle error
        handleError(message.channel, message.error)
    }
    message.end -> {
        // Clean up channel resources
        closeChannel(message.channel)
    }
}
```

### Best Practices

1. **Unique channel identifiers**: Use descriptive, unique channel IDs to avoid conflicts (e.g., "user-123-notifications", "chat-room-general")

2. **Validate state transitions**: Ensure channels are started before sending data, and properly closed when finished

3. **Only one of data or error**: A message should contain either `data` or `error`, not both

4. **Path and queryParams only on start**: Only include `path` and `queryParams` in start messages, not in regular data messages

5. **Handle errors gracefully**: When receiving an error message, clean up channel state and inform the user appropriately

## Serialization

All types in core-shared are annotated with `@Serializable` and work seamlessly with KotlinX Serialization:

```kotlin
// Encoding
val error = LSError(http = 404, detail = "not-found")
val json = Json.encodeToString(error)
// Result: {"http":404,"detail":"not-found","message":"","data":""}

// Decoding
val decoded = Json.decodeFromString<LSError>(json)
```

## See Also

- [Typed Endpoints](typed-endpoints.md) - Using LSError in API definitions
- [WebSockets](websockets.md) - WebSocket handling in Lightning Server
- [HTTP Basics](http.md) - HTTP request and response handling
