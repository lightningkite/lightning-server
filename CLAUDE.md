# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Lightning Server is a Kotlin-based server framework that drastically speeds up server development, comparable to Django
for Python. It's built to work across multiple serverless platforms with extensive abstraction layers for databases,
caching, file storage, email, SMS, and more.

**Current Version**: `version-5-SNAPSHOT`

**Main Branch**: `master` (PRs should target this)

## Build System

This is a Gradle-based multi-module Kotlin project.

### Common Commands

```bash
# Build all modules
./gradlew build

# Clean build artifacts
./gradlew clean

# Run all checks (tests, linting)
./gradlew check

# Assemble without running tests
./gradlew assemble

# Run the demo server (Ktor engine)
./gradlew :demo:run --args="serve"

# Run the demo server (Netty engine)
./gradlew :demo:run --args="serveNetty"

# Run the demo server (JDK engine)
./gradlew :demo:run --args="serveJdk"

# Generate SDK from demo
./gradlew :demo:run --args="sdk"

# Run tests for a specific module
./gradlew :core:test

# Publish to local Maven
./gradlew publishToMavenLocal
```

### Testing

Unit tests use mock services to avoid external dependencies. Tests should:

- Use the `ServerBuilder.test { }` extension for testing endpoints
- Use `JsonFileDatabase` or similar mock implementations for services
- Be runnable without any external service dependencies
- Test typed endpoints using `apiHandler.test(auth = ..., input = ...)` inside a `test { }` block
- Test raw HTTP handlers using `handler.test()` inside a `test { }` block

Example test pattern:

```kotlin
class ServerTest {
    @Test
    fun testEndpoint() {
        Server.test(
            settings = {
                // configure settings, e.g.: database.set(Database.Settings("ram"))
            }
        ) {
            runBlocking {
                val response = Server.someEndpoint.test()
                assertEquals(expectedValue, response.body!!.text())
            }
        }
    }
}
```

For typed endpoints requiring authentication:

```kotlin
Server.test(settings = {}) {
    runBlocking {
        val user = User(email = "test@example.com")
        Server.userInfo.table().insertOne(user)

        val auth = Server.userPrincipal.testAuth(user)
        val result = Server.someProtectedEndpoint.test(auth = auth, input = Unit)
        assertEquals(expectedValue, result)
    }
}
```

`testAuth` is a `context(server: ServerRuntime)` extension on `PrincipalType`, so it must be called
inside a `test { }` block where a `ServerRuntime` is in context.

#### Common Testing Pitfalls

**Duplicate UploadEarlyEndpoint declarations**: If you create multiple instances of `UploadEarlyEndpoint` (e.g., in
different modules or test files), they will have conflicting declarations for how `ServerFile` is serialized. This
causes runtime serialization errors that manifest as `500 Internal Server Error` responses in tests, even though the
code compiles successfully.

**Solution**: Only instantiate `UploadEarlyEndpoint` once in your server definition and reference it from tests. Do not
create separate instances for testing.

## Architecture

### Module Structure

The project follows a **paired module pattern**: most features have both a JVM module and a `-shared` multiplatform
module:

- **Core modules** (`core`, `core-shared`): Base server definitions, HTTP handling, settings, serialization
- **Typed modules** (`typed`, `typed-shared`): Type-safe API endpoint definitions with auto-generated documentation and
  SDKs
- **Auth modules** (`auth`, `auth-shared`): Pre-built authentication functionality
- **Session modules** (`sessions`, `sessions-shared`, `sessions-email`, `sessions-sms`): Session management with various
  authentication methods (email magic links, PIN codes, SMS, OAuth)
- **File modules** (`files`, `files-shared`): File upload/download handling with multiple backend support
- **Media modules** (`media`, `media-shared`): Media processing capabilities
- **Engine modules**: Different deployment targets
    - `engine-local`: For unit testing
    - `engine-ktor`: Ktor-based HTTP server (recommended for development)
    - `engine-netty`: Netty-based HTTP server
    - `engine-jdk-server`: Pure JDK HTTP server
    - `engine-aws-serverless`: AWS Lambda deployment with Terraform generation
- **Secret sources** (`secret-source-aws`): Integration with AWS Secrets Manager

### Server Definition Pattern

All Lightning Server applications follow this pattern:

1. **Define a ServerBuilder object** - This is your central server definition:

```kotlin
object Server : ServerBuilder() {
    // Settings
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    // Endpoints
    val root = path.get bind HttpHandler {
        HttpResponse.plainText("Hello world!")
    }

    val api = path.path("api") include ApiEndpoints
}
```

2. **Create endpoint groups** - Organize related endpoints into separate objects:

```kotlin
object ApiEndpoints : ServerBuilder() {
    val example = path.path("example").get bind HttpHandler {
        HttpResponse.json(ExampleData())
    }
}
```

3. **Set up an engine** - Choose an engine based on deployment target:

```kotlin
fun main() {
    val built = Server.build()
    KtorEngine(built).apply {
        settings.loadFromFile(KFile("settings.json"), internalSerializersModule)
        start(Netty)
    }
}
```

### Endpoint Definition

Endpoints are defined using a fluent path-building syntax:

```kotlin
// Simple endpoint: GET /
val root = path.get bind HttpHandler { /* ... */ }

// Nested path: POST /api/users
val createUser = path.path("api").path("users").post bind HttpHandler { /* ... */ }

// Path with argument: GET /users/{id}
val getUser = path.path("users").arg<String>("id").get bind HttpHandler { request ->
    val id = request.path.arg1  // Type-safe access to first argument
    // ...
}

// Multiple arguments: GET /users/{userId}/posts/{postId}
val getUserPost = path.path("users").arg<String>("userId")
    .path("posts").arg<Int>("postId").get bind HttpHandler { request ->
    val userId = request.path.arg1  // First argument (String)
    val postId = request.path.arg2  // Second argument (Int)
    // ...
}
```

### Typed Endpoints

Use typed endpoints for API development to get automatic documentation, SDK generation, and OpenAPI specs:

```kotlin
val typedEndpoint = path.path("api").path("action").post.api(
    summary = "Short description",
    description = "Detailed description of what this endpoint does",
    authOptions = noAuth,  // or authOptions<User>() for authenticated endpoints
    errorCases = listOf(
        LSError(http = 404, detail = "not-found", message = "Resource not found")
    ),
    successCode = HttpStatus.OK,
    implementation = { input: InputType ->
        // Implementation here
        return@api OutputType()
    }
)
```

### Database Access

Models use KotlinX Serialization with additional annotations:

```kotlin
@Serializable
@GenerateDataClassPaths  // Required for query DSL
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val author: String,
    val body: String,
    val updatedAt: Instant = Clock.System.now()
) : HasId<Uuid>
```

Database operations use a DSL for type-safe queries. Register each table once in your `ServerBuilder`
with `registerTable` — one call defines it, registers it, and creates its once-per-deploy prepare task.
The returned value is a runtime accessor; invoke it inside a handler to get the `Table`:

```kotlin
// in your ServerBuilder:
val postTable = database.registerTable<Post>("Post")   // define + register + prepare, once

// inside a handler:
val posts = postTable()

// Insert
posts.insertOne(Post(title = "Test", author = "user@example.com", body = "Content"))

// Query
posts.find(condition { it.title eq "Test" }).toList()

// Update
posts.updateOne(
    condition { it.title eq "Test" },
    modification { it.title assign "Updated Title" }
)

// Delete
posts.deleteMany(condition { it.author eq "user@example.com" })
```

### Service Abstractions

The framework provides abstractions for common services that can be swapped via settings:

- **Database**: MongoDB, Postgres (partial), JSON files, RAM mock
- **Cache**: Redis, Memcached, DynamoDB, RAM mock
- **Files**: Local, AWS S3, Azure Blob Storage
- **Email**: SMTP, Amazon SES, console mock
- **SMS**: Twilio, console mock

Services are configured via `settings.json` which is automatically generated on first run. The framework principle is
that applications should work out-of-the-box with the generated settings file.

## Development Workflow

### Initial Setup

When setting up a new Lightning Server project, running the application twice is standard:

1. First run generates `settings.json` with defaults
2. Second run uses the settings file and starts normally

### Running the Demo

The `demo` module serves as both a reference implementation and testing ground:

```bash
# Run with Ktor (recommended for development)
./gradlew :demo:run --args="serve"

# Access at http://localhost:8080
```

### Generating Client SDKs

The framework can auto-generate TypeScript and Kotlin SDKs from typed endpoints:

```bash
./gradlew :demo:run --args="sdk"
```

### AWS Deployment

The AWS serverless engine generates Terraform configuration automatically:

1. Include `engine-aws-serverless` in dependencies
2. Build your application
3. Run the AWS handler which generates Terraform in `terraform/` directory
4. Terraform handles Lambda functions, API Gateway, DynamoDB tables, S3 buckets, etc.

## Important Principles

1. **Settings file should work out-of-the-box**: Generated `settings.json` should allow the application to run
   immediately without manual configuration
2. **Use abstractions**: Prefer service abstractions over direct implementations to maintain deployment flexibility
3. **Test with mocks**: Unit tests should use mock service implementations (JsonFileDatabase, RAM cache, etc.)
4. **Store endpoint references**: Always store endpoint definitions in constants for testing and internal calls
5. **Group endpoints logically**: Use separate ServerBuilder objects for different API sections
6. **Type safety**: Use `@GenerateDataClassPaths` on all database models for type-safe queries
7. **Document typed endpoints**: Use the typed endpoint API with good summaries and descriptions for auto-generated
   documentation

## Key Files to Reference

- `demo/src/main/kotlin/com/lightningkite/lightningserver/demo/Server.kt` - Comprehensive example server definition
- `docs/setup.md` - Initial project setup guide
- `docs/endpoints.md` - Endpoint definition patterns
- `docs/typed-endpoints.md` - Typed API documentation
- `docs/database.md` - Database usage and query DSL
- `docs/authentication.md` - Auth setup and patterns

## Common Patterns

### Accessing Services

Services are accessed through settings defined in your ServerBuilder:

```kotlin
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val files = setting("files", Files.Settings())
    val email = setting("email", Email.Settings())

    val someEndpoint = path.get bind HttpHandler {
        val db = database()
        val cacheService = cache()
        // Use services...
    }
}
```

### Error Handling

Use standard HTTP exceptions:

```kotlin
throw BadRequestException("Invalid input")
throw NotFoundException("Resource not found")
throw UnauthorizedException("Authentication required")
```

### Middleware/Interceptors

The framework supports interceptors for cross-cutting concerns like CORS, authentication, logging, etc. These are
configured in your ServerBuilder.
