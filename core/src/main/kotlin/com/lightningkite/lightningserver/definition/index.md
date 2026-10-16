# Server Definition Package

The `definition` package contains the core types and builders for defining Lightning Server applications. This package
provides the foundation for creating server definitions, including endpoints, tasks, schedules, settings, and the
extensibility system.

## Package Structure

### Core Files

- **`ServerBuilder.kt`** - The primary entry point for defining servers. Provides a DSL for registering endpoints,
  tasks, schedules, and settings. All Lightning Server applications extend `ServerBuilder` to define their structure.

- **`ServerDefinition.kt`** - The immutable runtime representation of a server, produced by building a `ServerBuilder`.
  Contains all endpoints, tasks, schedules, and settings organized in a flattened structure for efficient routing and
  execution.

- **`endpoints.kt`** - Defines `ServerPathEndpoints`, which represents all handlers (HTTP and WebSocket) registered at a
  specific path.

### Extension System

- **`Extensions.kt`** - Core extension system providing type-safe storage for arbitrary data on server components.
  Includes:
    - `Extensions` - Read-only extension access
    - `MutableExtensions` - Read-write extension access
    - `Extended` - Interface for types that provide extensions
    - `Extendable` - Interface for types with mutable extensions
    - `Extensions.Key` - Type-safe keys for extension values
    - `MutableExtensions.DegradingKey` - Keys that provide different types for read vs. write access

- **`Extensions.ext.kt`** - Extension functions and delegates for working with the extension system, including property
  delegation support.

### Tasks and Execution

- **`Task.kt`** - Background tasks that can be invoked with serializable input data. Useful for async operations like
  sending emails, processing uploads, or long-running work.

- **`StartupTask.kt`** - Tasks that execute once during server initialization, with dependency-based ordering. Used for
  migrations, cache warming, and other startup operations.

- **`ScheduledTask.kt`** - Tasks that run on a schedule (frequency, daily, or cron-based). Used for cleanup jobs, report
  generation, data synchronization, etc.

- **`PreDeployTask.kt`** - Tasks that run once per deploy, before any instance serves, with dependency-based ordering.
  Used for work that must not race between instances, such as preparing database tables.

The bodies of all four kinds are `@OverrideOnly`: the framework runs each one as its own execution through the server's
interceptors.

### Settings and Configuration

- **`ServerSetting.kt`** - Type-safe settings that can be configured via `settings.json`. Includes `Runtime` and
  `RuntimeDeferred` for accessing settings values.

- **`globalSettings.kt`** - Built-in global settings including:
    - `secretBasis` - Encryption key for sensitive data
    - `generalSettings` - Server URLs, debug mode, etc.
    - `telemetrySettings` - OpenTelemetry configuration
    - `loggingSettings` - Logging configuration

- **`GeneralServerSettings.kt`** - Configuration data class for general server settings like URLs, debug mode, and
  project name.

### Utilities

- **`Locationed.kt`** - Pairs an item with its location (path), implementing `Map.Entry` for convenience. Used to track
  where modules and endpoints are registered.

### Builder Utilities (`builder/` subdirectory)

- **`ListRegistry.kt`** - An append-only list used during server building. Items can be added but not removed, providing
  a safer API than `MutableList`.

- **`MapRegistry.kt`** - A write-once map that throws `DuplicateRegistrationError` if you attempt to register the same
  location twice. Prevents accidental endpoint overwrites.

- **`ServerBuilder.kt`** - See Core Files above (this is the main builder class).

## Key Concepts

### Server Building

Lightning Server applications are built using the builder pattern:

1. **Define** - Extend `ServerBuilder` and use the DSL to define endpoints, tasks, and settings
2. **Build** - Call `.build()` to create an immutable `ServerDefinition`
3. **Run** - Pass the `ServerDefinition` to an engine (Ktor, Netty, AWS Lambda, etc.)

### Extension System

The extension system allows you to attach arbitrary typed data to server components without modifying their core
interfaces. This is used throughout Lightning Server for features like CORS configuration, authentication requirements,
and OpenAPI documentation.

### Task Types

Lightning Server provides four types of tasks:

- **Tasks** - Asynchronous background work with serializable input, launched from a running execution
- **StartupTasks** - Per-instance initialization with dependency ordering
- **PreDeployTasks** - Once-per-deploy work with dependency ordering
- **ScheduledTasks** - Recurring work on a schedule

### Settings

Settings are configured in `settings.json` and accessed via `ServerSetting` instances. The settings system supports
transformation (e.g., converting settings data into live service instances) and can generate Terraform configuration for
cloud deployments.

## Usage Example

```kotlin
object Server : ServerBuilder() {
    // Define settings
    val database = setting("database", Database.Settings())
    val userTable = database.registerTable<User>("User")
    
    // Define endpoints
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello World!")
    }
    
    val api = path.path("api").path("users").get bind HttpHandler { request ->
        // Access a registered table
        val users = userTable().find(Condition.Always).toList()
        HttpResponse(body = users.toTypedData(request.headers.accept))
    }
    
    // Define a task
    val sendEmail = path.path("tasks").path("send-email") bind Task { input: EmailInput ->
        // Runs in the background, as a child of the execution that launched it
        deliverEmail(input)
    }
    
    // Define a scheduled task
    val cleanup = path.path("schedules").path("cleanup") bind ScheduledTask(
        frequency = 1.hours
    ) {
        // Cleanup old data every hour
        userTable().deleteMany(condition { it.deactivatedAt lt now() - 30.days })
    }
}

fun main() {
    KtorEngine(Server.build()).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start(Netty)
    }
}
```

## See Also

- [Setup](../../../../../../../../docs/setup.md) - Getting started with Lightning Server
- [Endpoints](../../../../../../../../docs/endpoints.md) - Defining HTTP and WebSocket endpoints
- [Tasks](../../../../../../../../docs/tasks.md) - Working with tasks and schedules
- [Settings](../../../../../../../../docs/settings.md) - Configuring server settings
