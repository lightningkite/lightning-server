# Typed Endpoints

Last updated October 30, 2024 (`version-5`)

Typed endpoints provide type-safe, documented API endpoints with automatic serialization, validation, and SDK generation. This is the recommended way to build APIs in Lightning Server.

## Overview

The `typed` and `typed-shared` modules provide tools for creating REST and WebSocket APIs with:
- **Type safety**: Compile-time checked input and output types
- **Automatic serialization**: JSON, CBOR, CSV support with content negotiation
- **Input validation**: Annotation-based validation with detailed error messages
- **Authentication**: Integrated auth requirements with scope-based authorization
- **SDK generation**: Automatic TypeScript and Kotlin client SDK generation
- **Documentation**: OpenAPI/Swagger schema generation
- **Real-time updates**: WebSocket support for live data subscriptions

## Creating a Simple Endpoint

Use `ApiHttpHandler` to create type-safe endpoints:

```kotlin
import com.lightningkite.lightningserver.typed.ApiHttpHandler

object MyApi : ServerBuilder() {
    val hello = path.path("hello").get bind ApiHttpHandler<_, Unit?, Unit, String>(
        summary = "Hello World",
        description = "Returns a greeting message",
        auth = noAuth,
        implementation = {
            "Hello, World!"
        }
    )
}
```

This creates a `GET /hello` endpoint that returns a string.

## Endpoint with Input

To accept input, specify an input type:

```kotlin
@Serializable
data class GreetRequest(
    val name: String
)

val greet = path.path("greet").post bind ApiHttpHandler<_, Unit?, GreetRequest, String>(
    summary = "Greet User",
    description = "Returns a personalized greeting",
    auth = noAuth,
    implementation = { input ->
        "Hello, ${input.name}!"
    }
)
```

For GET requests, input is parsed from query parameters. For POST/PUT/PATCH, input comes from the request body.

## Path Parameters

Use path parameters for resource IDs:

```kotlin
val getUser = path.path("users").arg<String>("userId").get bind ApiHttpHandler<_, User?, Unit, User>(
    summary = "Get User",
    description = "Retrieves a user by ID",
    auth = authOptions<User>(),
    errorCases = listOf(
        LSError(http = 404, detail = "not-found", message = "User not found")
    ),
    implementation = { _ ->
        val userId = route.arg1 // Access first path parameter
        database().users.get(userId) ?: throw NotFoundException()
    }
)
```

Access path parameters via `route.arg1`, `route.arg2`, etc.

## Authentication

Endpoints can require authentication:

```kotlin
val protected = path.path("protected").get bind ApiHttpHandler<_, User, Unit, String>(
    summary = "Protected Resource",
    auth = authOptions<User>(), // Requires authenticated User
    implementation = {
        "Hello, ${auth.user.email}!" // auth.user is non-null
    }
)
```

Use `auth.user` to access the authenticated user.

## Input Validation

Add validation annotations to your input types:

```kotlin
@Serializable
data class CreateUserRequest(
    @StringLength(3, 50)
    val username: String,

    @EmailPattern
    val email: String,

    @IntRange(13, 120)
    val age: Int
)
```

Validation happens automatically before your implementation is called. Invalid input returns 400 Bad Request with detailed error messages.

## Error Handling

Document possible errors for better API documentation:

```kotlin
val update = path.path("items").arg<String>("id").put bind ApiHttpHandler<_, User?, Item, Item>(
    summary = "Update Item",
    auth = authOptions<User>(),
    errorCases = listOf(
        LSError(404, "not-found", "Item not found"),
        LSError(403, "forbidden", "You don't have permission to update this item"),
        LSError(400, "validation-failed", "Invalid item data")
    ),
    implementation = { input ->
        // Implementation...
    }
)
```

Throw standard exceptions:
- `NotFoundException()` → 404
- `ForbiddenException()` → 403
- `BadRequestException()` → 400
- `UnauthorizedException()` → 401

## Model REST Endpoints

For database models, use `ModelRestEndpoints` to generate a full CRUD API:

```kotlin
@Serializable
@GenerateDataClassPaths
data class Post(
    override val _id: Uuid = Uuid.random(),
    val title: String,
    val author: String,
    val content: String,
    val createdAt: Instant = Clock.System.now()
) : HasId<Uuid>

object MyApi : ServerBuilder() {
    val database = setting("database", Database.Settings())

    val postsInfo = database.modelInfo<User?, Post, Uuid>(
        auth = authOptions<User>(),
        permissions = {
            // TODO: WARNING!  This exposes all create, edit, delete, and read capabilities!
            // We probably want something more restrictive, even for a demonstration.
            ModelPermissions.all()
        }
    )

    val posts = path.path("posts") include ModelRestEndpoints(postsInfo)
}
```

This automatically creates endpoints for:
- `GET /posts` - Query/list posts
- `POST /posts` - Create post
- `GET /posts/{id}` - Get post by ID
- `PUT /posts/{id}` - Replace post
- `PATCH /posts/{id}` - Modify post
- `DELETE /posts/{id}` - Delete post
- `POST /posts/count` - Count posts
- `POST /posts/aggregate` - Aggregate numeric fields
- And more...

## Real-time Model Updates

Add WebSocket support for real-time updates to your REST endpoints. This allows clients to receive real-time notifications when models are created, updated, or deleted.

### Using ModelRestEndpointsAndUpdatesWebsocket

```kotlin
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebsocket

val postsWithUpdates = path.path("posts") include ModelRestEndpointsAndUpdatesWebsocket(postsInfo)
```

### Using the Plus Operator

Alternatively, combine endpoints and websocket separately using the `+` operator:

```kotlin
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.ModelRestUpdatesWebsocket
import com.lightningkite.lightningserver.typed.ModelRestEndpointsAndUpdatesWebsocket.Companion.plus

val rest = path.path("rest") module (
    ModelRestEndpoints(postsInfo) + ModelRestUpdatesWebsocket(postsInfo)
)
```

Both patterns create the same endpoints, including a WebSocket endpoint at `/posts/rest/updates` (or `/posts/updates` depending on your path structure) that sends real-time notifications.

### What Gets Generated

The WebSocket endpoint provides:
- Real-time notifications for create, update, and delete operations
- Automatic filtering based on user permissions
- Initial snapshot of existing data matching the query
- Incremental updates as changes occur

Clients can subscribe to collection changes via WebSocket and receive live updates.

## SDK Generation

The typed endpoints automatically expose their schema for SDK generation. See [meta endpoints](meta.md) for accessing the schema and generating clients.

## Testing

Test endpoints using the test extension:

```kotlin
@Test
fun testGreeting() = runBlocking {
    JsonFileDatabase // Ensure mock services loaded

    val engine = LocalEngine(MyApi.build())
    val response = MyApi.greet.test(engine, GreetRequest("Alice"))

    assertEquals("Hello, Alice!", response.body!!.text())
    assertEquals(200, response.status.code)
}
```

## Best Practices

1. **Use reified type parameters**: Use `ApiHttpHandler<...>()` for automatic serializer resolution
2. **Document errors**: Always list possible error cases
3. **Use ModelRestEndpoints**: For standard CRUD operations
4. **Validate input**: Use validation annotations
5. **Keep implementations focused**: Business logic only
6. **Use subscopes**: Organize auth with subscopes
7. **Store endpoint references**: Store in constants for testing and internal calls

## See Also

- [Authentication](authentication.md) - Setting up auth requirements
- [Database](database.md) - Working with models and queries
- [Meta Endpoints](meta.md) - Accessing API schema and documentation