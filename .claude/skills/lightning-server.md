# Lightning Server Development Skill

You are an expert Lightning Server developer. This skill helps you build robust Kotlin server applications using the Lightning Server framework.

## Framework Overview

Lightning Server is a Kotlin-based server framework for building APIs across multiple serverless platforms. It provides:
- Type-safe endpoint definitions with auto-generated documentation
- Database abstractions (MongoDB, Postgres, JSON files)
- Caching abstractions (Redis, Memcached, DynamoDB)
- File storage abstractions (S3, Azure, local)
- Authentication & authorization (email, SMS, OAuth, password, OTP)
- WebSocket support
- Background tasks and scheduled jobs
- Multi-platform SDK generation (TypeScript, Kotlin)
- OpenAPI documentation generation

**Version:** 5.x
**Main Branch:** master

## Core Concepts

### 1. ServerBuilder Pattern

All Lightning Server applications use the `ServerBuilder` pattern:

```kotlin
object MyServer : ServerBuilder() {
    // Settings
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    // Endpoints
    val hello = path.get bind HttpHandler {
        HttpResponse.plainText("Hello World!")
    }
}
```

### 2. Endpoint Definition

Endpoints use a fluent path-building syntax:

```kotlin
// Simple GET
val root = path.get bind HttpHandler { /* ... */ }

// With path parameter
val getUser = path.path("users").arg<String>("id").get bind HttpHandler { request ->
    val id = request.path.arg1  // Type-safe access
    // ...
}

// Multiple arguments
val getUserPost = path.path("users").arg<String>("userId")
    .path("posts").arg<Int>("postId").get bind HttpHandler { request ->
    val userId = request.path.arg1  // String
    val postId = request.path.arg2  // Int
    // ...
}
```

### 3. Typed Endpoints

Use typed endpoints for auto-documentation and SDK generation:

```kotlin
val createPost = path.path("posts").post.api(
    summary = "Create a blog post",
    description = "Creates a new blog post with the provided data",
    auth = noAuth,  // or auth<User>()
    errorCases = listOf(
        LSError(http = 400, detail = "invalid-input", message = "Title required")
    ),
    successCode = HttpStatus.Created,
    implementation = { input: CreatePostRequest ->
        // Type-safe implementation
        CreatePostResponse(...)
    }
)
```

### 4. Database Operations

**⚠️ IMPORTANT: Use ModelRestEndpoints for CRUD Operations**

For standard CRUD operations, use the pre-built `ModelRestEndpoints` rather than manually creating database endpoints:

```kotlin
// Define your model with @GenerateDataClassPaths
@Serializable
@GenerateDataClassPaths
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val content: String,
    val authorId: Uuid,
    val createdAt: Instant = Clock.System.now()
) : HasId<Uuid>

// Set up ModelInfo with auth and permissions
val postInfo = database.modelInfo(
    auth = UserAuth.require() or AuthRequirement.None,
    permissions = {
        val user = authOrNull?.fetch()
        val isOwner = condition { it.authorId eqNn user?._id }

        ModelPermissions(
            create = if (user != null) Condition.Always else Condition.Never,
            read = Condition.Always,
            update = isOwner,
            delete = isOwner
        )
    }
)

// Create REST endpoints automatically (provides list, get, create, update, delete, query)
val posts = path.path("posts").path("rest") module ModelRestEndpoints(postInfo)

// Optional: Add WebSocket updates for real-time changes
val postsWithWs = path.path("posts").path("rest") include
    ModelRestEndpoints(postInfo) + ModelRestUpdatesWebsocket(postInfo)
```

This gives you:
- `GET /posts/rest` - List with pagination, sorting, filtering
- `GET /posts/rest/{id}` - Get by ID
- `POST /posts/rest` - Create
- `PUT /posts/rest/{id}` - Update
- `DELETE /posts/rest/{id}` - Delete
- `POST /posts/rest/query` - Advanced querying
- `WS /posts/rest/watch` - Real-time updates (if WebSocket added)

**Manual Database Operations (use only when needed)**

Use low-level database operations for custom business logic beyond simple CRUD:

```kotlin
val posts = database().table<Post>()

// Insert
posts.insertOne(Post(title = "Hello", content = "World"))

// Query
posts.find(condition { it.title eq "Hello" }).toList()

// Update
posts.updateOne(
    condition { it._id eq id },
    modification { it.title assign "Updated" }
)

// Delete
posts.deleteMany(condition { it.authorId eq userId })

// Complex queries
posts.find(
    condition = condition {
        (it.title.contains("Kotlin")) and (it.createdAt gt yesterday)
    },
    orderBy = listOf(SortPart(Post.path.createdAt, false)),
    skip = page * pageSize,
    limit = pageSize
).toList()
```

Use manual operations when you need:
- Custom business logic beyond CRUD
- Complex queries not supported by ModelRestEndpoints
- Special validation or transformation logic
- Aggregations or computed fields

### 5. Authentication

Define a PrincipalType for your user model:

```kotlin
object UserAuth: PrincipalType<User, Uuid> {
    override val idSerializer = Uuid.serializer()
    override val subjectSerializer = User.serializer()
    override val name = "User"

    context(server: ServerRuntime)
    override suspend fun fetch(id: Uuid): User =
        database().table<User>().get(id) ?: throw NotFoundException()
}
```

Set up ModelInfo with permissions:

```kotlin
val userInfo: ModelInfo<User?, User, Uuid> = database.modelInfo(
    auth = UserAuth.require() or AuthRequirement.None,
    permissions = {
        val user = authOrNull?.fetch()
        val self = condition { it._id eqNn user?._id }
        val admin = if (user?.isSuperUser == true) Condition.Always else Condition.Never

        ModelPermissions(
            create = Condition.Never,
            read = Condition.Always,
            update = self or admin,
            delete = admin
        )
    }
)
```

Configure proof methods:

```kotlin
val pins = PinHandler(cache, "pins")
val proofEmail = path.path("proof").path("email") module
    EmailProofEndpoints(pins, email, { to, pin ->
        Email(subject = "Login Code", to = listOf(EmailAddressWithName(to)),
              plainText = "Your PIN is $pin")
    })
val proofPassword = path.path("proof").path("password") module
    PasswordProofEndpoints(database, cache)
```

Set up AuthEndpoints:

```kotlin
val auth = path.path("auth") module object: AuthEndpoints<User, Uuid>(
    principal = UserAuth,
    database = database
) {
    context(server: ServerRuntime)
    override suspend fun requiredProofStrengthFor(subject: User): Int = 5

    context(server: ServerRuntime)
    override suspend fun sessionExpiration(subject: User): Instant? = null
}
```

### 6. File Handling

```kotlin
val files = setting("files", PublicFileSystem.Settings())

// Upload (early binding)
val uploadEarly = path.path("upload") module
    UploadEarlyEndpoint(files, database, Runtime.Constant(listOf()))

// Get signed URL
val getFile = path.path("files").arg<String>("path").get bind HttpHandler {
    val filePath = it.arg1
    val fileRef = files().root.then(filePath)
    HttpResponse.plainText(fileRef.signedUrl)
}
```

### 7. WebSockets

```kotlin
val topic = path.path("topic").topic(Message.serializer())

val socket = path.path("ws") bind WebSocketHandler(
    willConnect = { Uuid.random().toString() },
    didConnect = {
        subscribe(topic)
        send(WelcomeMessage())
    },
    messageFromClient = {
        topic.send(Message(currentState, it.content))
    },
    topicHandlers = {
        topic bind { send(it.value) }
    },
    disconnect = {
        println("Disconnected: $currentState")
    }
)
```

### 8. Background Tasks

```kotlin
// Define task
val emailTask = path.path("tasks").path("email") bind Task { input: EmailRequest ->
    println("Sending email to ${input.to}")
    delay(1000)
    email().send(Email(subject = input.subject, to = listOf(EmailAddressWithName(input.to)),
                       plainText = input.body))
}

// Invoke task
val sendEmail = path.path("send-email").post bind HttpHandler { request ->
    emailTask.invoke(EmailRequest(request.body!!.text()))
    HttpResponse.plainText("Email queued")
}

// Scheduled task
val cleanup = path.path("scheduled-cleanup") bind ScheduledTask(
    frequency = 1.hours
) {
    println("Running cleanup...")
    database().table<OldData>().deleteMany(condition {
        it.createdAt lt Clock.System.now() - 30.days
    })
}
```

### 9. Caching

```kotlin
val cache = setting("cache", Cache.Settings())

// Set with expiration
cache().set("key", "value", expire = 5.minutes)

// Get
val value = cache().get<String>("key")

// Remove
cache().remove("key")

// Cache-aside pattern
suspend fun getExpensiveData(id: String): Data {
    val cached = cache().get<Data>("data:$id")
    if (cached != null) return cached

    val fresh = database().table<Data>().get(id)
    cache().set("data:$id", fresh, expire = 10.minutes)
    return fresh
}
```

### 10. Testing

**⚠️ CRITICAL: Build Server Once Per Test Suite**

When writing tests, ensure `Server.build()` is only called once across all tests to avoid `DuplicateRegistrationError`. Create a shared `TestHelper`:

```kotlin
// TestHelper.kt - shared across all test files
object TestHelper {
    val testRunner by lazy { TestRunner(Server.build()) }
}

// In your test file
class ServerTest {
    init {
        JsonFileDatabase  // Ensure mock implementations are loaded
    }

    @Test
    fun testEndpoint() = runBlocking {
        with(TestHelper.testRunner) {
            val response = Server.someEndpoint.test()
            assertEquals("expected", response.body!!.text())
        }
    }
}
```

**Test Method Signatures**

For basic `HttpHandler` endpoints:
```kotlin
// No path args
Server.endpoint.test(
    queryParameters = QueryParameters(listOf("key" to "value")),
    body = TypedData.text("content", MediaType.Text.Plain)
)

// With path args
Server.endpoint.test(
    "pathArg1",
    42,  // pathArg2
    queryParameters = QueryParameters.EMPTY
)
```

For `ApiHttpHandler` endpoints:
```kotlin
// No path args
Server.typedEndpoint.test(auth = null, input = RequestData(...))

// With path args
Server.typedEndpoint.test("pathArg", auth = null, input = RequestData(...))
```

**Common Testing Pitfalls**

⚠️ **Duplicate UploadEarlyEndpoint Declarations**

If you create multiple instances of `UploadEarlyEndpoint` (e.g., in different modules or endpoints), they will have **conflicting declarations for how `ServerFile` is serialized**. This causes runtime serialization errors that manifest as `500 Internal Server Error` responses in tests, even though the code compiles successfully.

**Solution:** Only instantiate `UploadEarlyEndpoint` once in your server definition:

```kotlin
object Server : ServerBuilder() {
    // ✅ Good - single instance
    val uploadEarly = path.path("upload") module
        UploadEarlyEndpoint(files, database, Runtime.Constant(listOf()))

    // ❌ Bad - creates duplicate with conflicting ServerFile serialization
    // val anotherUpload = path.path("upload2") module
    //     UploadEarlyEndpoint(files, database, Runtime.Constant(listOf()))
}
```

If you need multiple upload endpoints, reuse the same `UploadEarlyEndpoint` instance or use different endpoint patterns.

## Common Patterns

### Organizing Endpoints

Group related endpoints into ServerBuilder objects:

```kotlin
object ApiEndpoints : ServerBuilder() {
    val posts = path.path("posts") include PostsEndpoints
    val comments = path.path("comments") include CommentsEndpoints
}

object Server : ServerBuilder() {
    val api = path.path("api") include ApiEndpoints
}
```

### Error Handling

Use standard exceptions:

```kotlin
throw BadRequestException("Invalid input")
throw NotFoundException("Resource not found")
throw UnauthorizedException("Auth required")
throw ForbiddenException("Access denied")
```

### Accessing Services

Services are accessed through settings:

```kotlin
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())

    val endpoint = path.get bind HttpHandler {
        val db = database()
        // Use db...
    }
}
```

### Path Reference Pattern

Always store endpoint references for testing and internal calls:

```kotlin
object Server : ServerBuilder() {
    val createPost = path.path("posts").post bind ApiHttpHandler { ... }
    val getPost = path.path("posts").arg<Uuid>("id").get bind ApiHttpHandler { ... }

    // Can reference: Server.createPost, Server.getPost
}
```

## Build System

Lightning Server uses Gradle with Kotlin Multiplatform:

### Common Commands

```bash
# Build all modules
./gradlew build

# Run tests
./gradlew check

# Run demo server
./gradlew :demo:run --args="serve"

# Generate SDK
./gradlew :demo:run --args="sdk"

# Publish to local Maven
./gradlew publishToMavenLocal
```

### Module Structure

Projects typically have paired modules:
- `module` - JVM-only code (server implementation)
- `module-shared` - Multiplatform code (shared models, DTOs)

## Deployment

### Engines

Lightning Server supports multiple engines:

- `engine-local` - For unit testing
- `engine-ktor` - Ktor HTTP server (dev/prod)
- `engine-netty` - Netty HTTP server
- `engine-jdk-server` - Pure JDK HTTP server
- `engine-aws-serverless` - AWS Lambda with Terraform generation

### AWS Deployment

The AWS engine auto-generates Terraform:

```kotlin
fun main() {
    val built = Server.build()
    AwsHandler(built).apply {
        settings.loadFromFile(KFile("settings.json"))
        // Generates terraform/ directory
    }
}
```

### Settings Management

First run generates `settings.json`:

```json
{
  "database": {
    "url": "mongodb://localhost:27017/mydb"
  },
  "cache": {
    "url": "redis://localhost:6379"
  }
}
```

## Best Practices

1. **Use ModelRestEndpoints for CRUD** - Don't manually create database CRUD endpoints; use ModelRestEndpoints
2. **Settings File Works Out-of-Box** - Generated settings should allow immediate running
3. **Use Service Abstractions** - Don't depend on specific implementations
4. **Test with Mocks** - Use JsonFileDatabase, RAM cache for tests
5. **Store Endpoint References** - Keep constants for all endpoints
6. **Group Endpoints Logically** - Use ServerBuilder objects
7. **Type Safety** - Use @GenerateDataClassPaths on all database models
8. **Document Typed Endpoints** - Add summaries and descriptions

## Anti-Patterns

❌ **Don't manually create CRUD endpoints** - Use ModelRestEndpoints instead
❌ **Don't create multiple UploadEarlyEndpoint instances** - Causes ServerFile serialization conflicts
❌ **Don't call Server.build() multiple times in tests** - Use shared TestHelper with lazy initialization
❌ Don't access database implementations directly
❌ Don't hardcode configuration
❌ Don't skip endpoint reference storage
❌ Don't forget @GenerateDataClassPaths
❌ Don't use plain HttpHandler for APIs (use typed endpoints)
❌ Don't test against real services
❌ Don't write manual list/get/create/update/delete endpoints when ModelRestEndpoints can do it

## Key Files to Reference

- `demo/src/main/kotlin/.../Server.kt` - Comprehensive example
- `docs/setup.md` - Project setup
- `docs/endpoints.md` - Endpoint patterns
- `docs/typed-endpoints.md` - Typed API docs
- `docs/database.md` - Database usage
- `docs/authentication.md` - Auth setup

## Getting Help

When stuck:
1. Check the demo server for examples
2. Review relevant docs in `/docs`
3. Look at existing endpoint implementations
4. Check test files for usage patterns
5. Examine the CLAUDE.md file for project-specific guidance

## Usage

Invoke this skill when you need to:
- Build Lightning Server endpoints
- Set up authentication
- Work with databases
- Handle file uploads
- Implement WebSockets
- Create background tasks
- Write tests
- Deploy to AWS

Simply say: "Help me build a Lightning Server endpoint" or "How do I set up auth in Lightning Server?"
