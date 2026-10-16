# Endpoints

This guide covers everything you need to know about defining HTTP endpoints in Lightning Server, from basic routing to
advanced request/response handling.

First, you won't get very far in this section without some knowledge of HTTP. One tutorial you could go to for general
HTTP information is [this one I found](https://dev.to/abbeyperini/a-beginners-guide-to-http-part-1-definitions-38m7) by
Abbey Perini.

## Routing Basics

The typical way of defining routes is as follows:

```kotlin
object Server : ServerBuilder() {
    // GET /
    val root = path.get bind HttpHandler { /*...*/ }

    // POST /
    val create = path.post bind HttpHandler { /*...*/ }

    // PATCH /test
    val update = path.path("test").patch bind HttpHandler { /*...*/ }

    // DELETE /first/second/last
    val delete = path.path("first").path("second").path("last").delete bind HttpHandler { /*...*/ }

    // PUT /model/{id}/test - with path argument
    val putWithArg = path.path("model").arg<String>("id").path("test").put bind HttpHandler { /*...*/ }
}
```

**Important:** Always store the endpoint reference in a constant. This is useful for testing and for calling endpoints
internally.

```kotlin
val endpointReference = path.path("path-string-here").get bind HttpHandler {
    // implementation
}
```

### Building Paths

Start from your current path (the root as defined in `Server`):

```kotlin
path.path("some-path")  // adds a constant path segment
```

The path string should contain a single segment name. To add multiple segments, chain `.path()` calls:

```kotlin
path.path("users").path("profile")  // /users/profile
```

For wildcard path segments, use `.arg<T>("name")`:

```kotlin
path.path("users").arg<String>("id")  // /users/{id}
```

**Path Matching:** Paths are matched preferring exact literal matches first, then typed wildcard segments.

### HTTP Methods

Pick an HTTP verb using property access:

```kotlin
val endpoint = path.path("resource").get     // GET
val endpoint = path.path("resource").post    // POST
val endpoint = path.path("resource").put     // PUT
val endpoint = path.path("resource").patch   // PATCH
val endpoint = path.path("resource").delete  // DELETE
val endpoint = path.path("resource").options // OPTIONS
val endpoint = path.path("resource").head    // HEAD
```

### Binding Handlers

Finally, bind a handler to respond to this endpoint:

```kotlin
val endpointReference = path.path("hello").get bind HttpHandler { request ->
    // Calculate a response here
    HttpResponse.plainText("Hello, World!")
}
```

**Tip:** In Kotlin, naming a single parameter to a lambda is optional. If you don't explicitly call it out, the name
will be `it`.

### Custom Timeouts

Handlers have a default timeout of 30 seconds. You can customize this:

```kotlin
val slowEndpoint = path.path("slow").get bind HttpHandler(timeout = 60.seconds) { request ->
    // Long-running operation
    HttpResponse.plainText("Done")
}
```

## Reading Request Information

Access request data via the `HttpRequest` parameter:

```kotlin
val endpoint = path.path("users").arg<String>("id").get bind HttpHandler { request ->
    // Path arguments (type-safe!)
    val userId = request.path.arg1  // Type: String

    // Query parameters
    val filter = request.queryParameters["filter"]
    val page = request.queryParameters["page"]?.toIntOrNull() ?: 1

    // Headers
    val contentType = request.headers.contentType
    val auth = request.headers[HttpHeader.Authorization]

    // Request body
    val body = request.body?.text()

    // Request metadata
    val clientIp = request.sourceIp
    val isSecure = request.protocol == "https"
    val hostname = request.domain

    HttpResponse.plainText("User $userId")
}
```

### Request Properties

- **path**: The resolved path with typed arguments (access via `arg1`, `arg2`, etc.)
- **queryParameters**: Query string parameters as QueryParameters
- **headers**: HTTP request headers as HttpHeaders
- **body**: Request body as TypedData (nullable)
- **domain**: Domain name from the request (e.g., "example.com")
- **protocol**: "http" or "https"
- **sourceIp**: Originating client IP address
- **cache**: Request-scoped cache, keyed by `Request.CacheKey`s such as `Authentication.CacheKey`

### Common Request Patterns

**Query parameters and filtering:**

```kotlin
val listItems = path.path("items").get bind HttpHandler { request ->
    val data = listOf(1, 2, 3, 4)
    val dataToRender = if(request.queryParameters["filter"] == "odd")
        data.filter { it % 2 == 1 }
    else
        data
    HttpResponse.plainText(dataToRender.joinToString())
}
```

**Reading the body:**

```kotlin
val postItem = path.path("items").post bind HttpHandler { request ->
    val numberToAdd = request.body!!.text().toIntOrNull()
        ?: throw BadRequestException("Invalid number")
    // Process the number...
    HttpResponse.plainText("Added $numberToAdd")
}
```

**Using path arguments:**

```kotlin
val getItem = path.path("items").arg<Int>("id").get bind HttpHandler { request ->
    val id = request.path.arg1  // Type-safe: Int
    HttpResponse.plainText("Item $id")
}
```

**Multiple path arguments:**

```kotlin
val getUserPost = path.path("users").arg<String>("userId")
    .path("posts").arg<Int>("postId").get bind HttpHandler { request ->
    val userId = request.path.arg1  // First argument (String)
    val postId = request.path.arg2  // Second argument (Int)
    HttpResponse.plainText("User $userId, Post $postId")
}
```

**Pagination:**

```kotlin
val listUsers = path.path("users").get bind HttpHandler { request ->
    val page = request.queryParameters["page"]?.toIntOrNull() ?: 1
    val limit = request.queryParameters["limit"]?.toIntOrNull() ?: 20

    val users = userTable()
        .find(condition { /* ... */ }, skip = (page - 1) * limit, limit = limit)
        .toList()

    HttpResponse(body = users.toTypedData(request.headers.accept))
}
```

**Reading JSON body:**

```kotlin
val createUser = path.path("users").post bind HttpHandler { request ->
    // parse() picks the registered decoder for the body's Content-Type (JSON, form data, etc.)
    val user = request.body?.parse(User.serializer()) ?: throw BadRequestException("Missing body")

    // Process user...

    // toTypedData() picks the registered encoder for the request's Accept header
    HttpResponse(body = user.toTypedData(request.headers.accept))
}
```

**Key Points:**

- `TypedData.parse(serializer)` - Deserializes based on Content-Type
- `value.toTypedData(accepts)` - Serializes for the best media type the client accepts
- Both use the media type coders registered on the server, so call `registerBasicMediaTypeCoders()` in your
  `ServerBuilder`'s `init`
- For JSON APIs, [typed endpoints](typed-endpoints.md) do all of this for you, and generate documentation and SDKs

See [Serialization Documentation](serialization.md) for more details on customizing serialization, adding custom media
types, and working with different formats.

## Creating Responses

An `HttpResponse` is made of a body, status code, and set of headers.

### Basic Response Construction

```kotlin
// Manual construction
HttpResponse(
    body = TypedData.text("Hello", MediaType.Text.Plain),
    status = HttpStatus.OK,
    headers = HttpHeaders {
        add("X-Custom-Header", "value")
    }
)

// Default status behavior
HttpResponse(body = TypedData.text("Hello", MediaType.Text.Plain))  // 200 OK (has body)
HttpResponse()  // 204 No Content (no body)
```

**Important:** The status defaults to 200 OK if a body is provided, or 204 No Content if the body is null. For other
status codes (like 201 Created), specify explicitly.

### Response Shortcuts

Extension functions provide convenient shortcuts:

```kotlin
HttpResponse.plainText("Some Text")
HttpResponse.redirectToGet("https://example.com")
HttpResponse.pathMoved("/new-location")
HttpResponse.pathMovedPermanently("/new-permanent-location")

HttpResponse.html {
    head {
        title { +"My Page" }
    }
    body {
        h1 { +"My First Heading" }
        p { +"My first paragraph." }
    }
}
```

### TypedData

The content is represented as `TypedData`, which includes both the data and its media type:

```kotlin
import com.lightningkite.services.data.TypedData

TypedData.text("Some text", MediaType.Text.Plain)
TypedData.text("{\"json\": true}", MediaType.Application.Json)
HttpResponse(body = TypedData.bytes(byteArray, MediaType.Application.OctetStream))
```

### HttpStatus Codes

Use named constants for common HTTP status codes:

```kotlin
HttpStatus.OK                    // 200
HttpStatus.Created               // 201
HttpStatus.NoContent             // 204
HttpStatus.BadRequest            // 400
HttpStatus.Unauthorized          // 401
HttpStatus.Forbidden             // 403
HttpStatus.NotFound              // 404
HttpStatus.InternalServerError   // 500

// Custom status codes
val custom = HttpStatus(418)  // I'm a teapot

// Check for success (2xx range)
if (response.status.success) {
    // Handle success
}

// String representation includes description
println(HttpStatus.OK)  // "200 OK"
```

## Working with Headers

`HttpHeaders` is an immutable collection with case-insensitive lookup.

### Creating Headers

```kotlin
// Builder DSL (recommended)
val headers = HttpHeaders {
    add("Content-Type", "application/json")
    add("X-Custom-Header", "value")
    setCookie(
        name = "session",
        value = "abc123",
        path = "/",
        httpOnly = true,
        secure = true,
        sameSite = HttpHeaders.SameSite.Strict
    )
}

// From pairs
val headers = HttpHeaders(
    "Content-Type" to "application/json",
    "X-Custom-Header" to "value"
)
```

### Reading Headers

```kotlin
// Single value (case-insensitive)
val contentType = headers["Content-Type"]
val auth = headers["authorization"]  // Same as "Authorization"

// Multiple values (for headers like Accept)
val acceptTypes = headers.getMany("Accept")

// Convenience accessors
val mediaType = headers.contentType      // Parsed MediaType
val length = headers.contentLength       // Parsed as Long
val accepts = headers.accept             // List of MediaType
val cookieMap = headers.cookies          // Map<String, String>
```

### Modifying Headers

```kotlin
// Combine headers
val combined = headers1 + headers2

// Copy with modifications
val modified = headers.copy {
    add("X-Additional", "value")
}

// Remove headers in builder
val headers = HttpHeaders {
    add("X-First", "value")
    remove("X-First")  // Remove it
}
```

### Setting Cookies

```kotlin
HttpHeaders {
    setCookie(
        name = "session",
        value = "token123",
        expiresAt = Clock.System.now() + 1.hours,
        maxAge = 3600,        // seconds
        domain = "example.com",
        path = "/",
        secure = true,        // HTTPS only
        httpOnly = true,      // No JavaScript access
        sameSite = HttpHeaders.SameSite.Strict  // CSRF protection
    )
}
```

**SameSite Options:**

- **Strict**: Cookie only sent in first-party context
- **Lax**: Cookie sent with top-level navigation and same-site requests
- **None**: Cookie sent in all contexts (requires Secure flag)

### Header Constants

Use `HttpHeader` constants to avoid typos:

```kotlin
request.headers[HttpHeader.Authorization]
request.headers[HttpHeader.ContentType]
request.headers[HttpHeader.UserAgent]

response.headers[HttpHeader.SetCookie]
response.headers[HttpHeader.AccessControlAllowOrigin]
```

Available categories:

- Standard HTTP headers (Accept, Content-Type, etc.)
- CORS headers (Access-Control-*)
- WebSocket headers (Sec-WebSocket-*)
- Common non-standard headers (X-Forwarded-*, X-Request-ID, etc.)

## URL Parsing Utilities

### QueryParameters

Represents parsed query string parameters with automatic URL decoding:

```kotlin
// Access query parameters
val filter = request.queryParameters["filter"]

// Parse manually if needed
val params = QueryParameters.parse("filter=active&sort=name")
println(params["filter"])  // "active"

// URL decoding is automatic
val params = QueryParameters.parse("name=john%20doe")
println(params["name"])  // "john doe"

// Multiple values for same key
val params = QueryParameters(listOf("tag" to "kotlin", "tag" to "server"))
val allTags = params.entries.filter { it.first == "tag" }

// Empty parameters
QueryParameters.EMPTY
```

### PathSegments

Represents URL path segments with automatic URL decoding:

```kotlin
val segments = PathSegments.parse("/api/users/123")
println(segments.segments)  // ["api", "users", "123"]

// URL decoding is automatic
val segments = PathSegments.parse("/users/john%20doe")
println(segments.segments)  // ["users", "john doe"]
```

## Grouping Endpoints

Organize related endpoints into separate objects:

```kotlin
object Server : ServerBuilder() {
    val api = path.path("api") include ApiEndpoints
}

object ApiEndpoints : ServerBuilder() {
    // GET /api/example
    val example = path.path("example").get bind HttpHandler {
        HttpResponse.plainText("example")
    }

    // GET /api/users
    val listUsers = path.path("users").get bind HttpHandler {
        HttpResponse.plainText("user1, user2")
    }
}
```

This format allows you to group and separate your endpoints effectively while still making routing centralized and
clear, as well as ensuring testing is still easy.

## Interceptors and Middleware

`HttpInterceptor` provides middleware functionality for cross-cutting concerns like authentication, logging, CORS, rate
limiting, etc.

### Creating Interceptors

```kotlin
object LoggingInterceptor : HttpInterceptor {
    context(runtime: ServerRuntime)
    override suspend fun <PATH : PathSpec> intercept(
        request: HttpRequest<PATH>,
        cont: suspend context(ServerRuntime) (HttpRequest<PATH>) -> HttpResponse,
    ): HttpResponse {
        val start = TimeSource.Monotonic.markNow()
        println("→ ${request.path}")
        val response = cont(request)
        println("← ${response.status} (${start.elapsedNow().inWholeMilliseconds}ms)")
        return response
    }
}

object RequireApiKey : HttpInterceptor {
    context(runtime: ServerRuntime)
    override suspend fun <PATH : PathSpec> intercept(
        request: HttpRequest<PATH>,
        cont: suspend context(ServerRuntime) (HttpRequest<PATH>) -> HttpResponse,
    ): HttpResponse {
        if (request.headers["X-Api-Key"] != apiKeySetting()) throw UnauthorizedException("Missing or invalid API key")
        return cont(request)
    }
}
```

For authentication, prefer registering an `Authentication.Reader` (see [Authentication](authentication.md)) over an
interceptor: auth is resolved on demand and cached on the request.

### Installing Interceptors

```kotlin
object Server : ServerBuilder() {
    init {
        install(LoggingInterceptor)
        install(RequireApiKey)
        install(CorsInterceptor(corsSettings))
    }
}
```

Interceptors run for every request, including the sub-requests inside `/meta/bulk`. An interceptor whose concern
belongs to the connection rather than to each request can check `runtime.execution.isRoot()`. To wrap every kind of
work, including tasks and schedules, install an `ExecutionInterceptor` instead (see [Runtime](runtime.md)).

### Interceptor Capabilities

Interceptors can:

- **Modify requests** before passing to next handler
- **Short-circuit** and return responses without calling next handler
- **Modify responses** after calling next handler
- **Add timing/logging** around request processing
- **Enforce authentication/authorization**
- **Add CORS headers**
- **Rate limit requests**

**Order matters:** Interceptors execute in the order they are installed; the first installed is the outermost. Put
cheaper checks (like CORS) before expensive ones.

Exceptions thrown by a handler are turned into responses before they reach the interceptors, so an interceptor sees an
error response rather than an exception, and can still post-process it (CORS headers, for example).

## Exception Handling

Lightning Server automatically converts exceptions to HTTP responses using `HttpExceptionHandler`.

### Default Behavior

- **HttpStatusException** → Appropriate status code with error details
- **Other exceptions in debug mode** → 500 with exception details and stack trace
- **Other exceptions in production** → 500 with generic error message

### Custom Error Responses

Throw an `HttpStatusException` subclass to choose the status, and give it a `detail` and `message` to shape the body:

```kotlin
throw BadRequestException(detail = "invalid-email", message = "That email address is not valid")
throw NotFoundException("No such user")
```

There is currently no registration API for a custom `HttpExceptionHandler`; every server uses
`HttpExceptionHandler.Default`.

## Advanced Patterns

### Content Negotiation

Handle different Accept headers to return appropriate formats:

`toTypedData` does this for you: it picks a registered encoder for the media types in the Accept header, falling back
to the server's default (JSON with the basic coders):

```kotlin
val getData = path.path("data").get bind HttpHandler { request ->
    val data = fetchData()
    HttpResponse(body = data.toTypedData(request.headers.accept))
}
```

To support another format, register a `MediaTypeCoder` for it (see [Serialization](serialization.md)).

### Conditional Requests

Support ETags for caching:

```kotlin
val getResource = path.path("resource").arg<String>("id").get bind HttpHandler { request ->
    val resource = resourceTable().get(request.path.arg1) ?: throw NotFoundException()
    val etag = resource.hashCode().toString()

    val clientETag = request.headers[HttpHeader.IfNoneMatch]?.root
    if (clientETag == etag) {
        return@HttpHandler HttpResponse(status = HttpStatus.NotModified)
    }

    HttpResponse(
        body = resource.toTypedData(request.headers.accept),
        headers = HttpHeaders {
            add(HttpHeader.ETag, etag)
            add(HttpHeader.CacheControl, "max-age=3600")
        }
    )
}
```

### Streaming Responses

For large files or streaming data:

```kotlin
val downloadFile = path.path("download").arg<String>("fileName").get bind HttpHandler { request ->
    val path = Path("exports", request.path.arg1)

    HttpResponse(
        // TypedData.source streams from a kotlinx-io Source instead of loading everything into memory
        body = TypedData.source(SystemFileSystem.source(path).buffered(), MediaType.Application.OctetStream),
        headers = HttpHeaders {
            add(HttpHeader.ContentDisposition, "attachment; filename=\"${request.path.arg1}\"")
        }
    )
}
```

## Handling HTML

While Lightning Server is mostly focused on creating API backends, you can also serve HTML using Kotlin's HTML DSL:

```kotlin
val homepage = path.get bind HttpHandler {
    HttpResponse.html {
        head {
            meta(charset = "utf-8")
            title { +"My Application" }
        }
        body {
            h1 { +"Welcome!" }
            p { +"This is my Lightning Server application." }
        }
    }
}
```

For serving static files, use the file system abstraction (see [Files documentation](files.md)).

## Best Practices

### Request Handling

1. **Use path arguments for resource IDs**: `path.path("users").arg<String>("id")`
2. **Use query parameters for filtering/pagination**: `request.queryParameters["page"]`
3. **Check content type before parsing body**: `request.headers.contentType`
4. **Validate input early**: Throw `BadRequestException` for invalid data
5. **Use type-safe path arguments**: The type system helps catch errors at compile time

### Response Construction

1. **Use appropriate status codes**: 201 Created, 204 No Content, 404 Not Found, etc.
2. **Choose the media type deliberately**: use `toTypedData` for content negotiation, or a `MediaType` constant
3. **Include Location header** for 201 Created responses
4. **Prefer typed endpoints** for JSON APIs over hand-built responses
5. **Be explicit about status codes** when they differ from defaults

### Headers

1. **Use HttpHeader constants** instead of string literals to avoid typos
2. **Set security headers**: install `SecurityHeadersInterceptor` for Strict-Transport-Security and
   X-Content-Type-Options
3. **Include CORS headers** when serving cross-origin requests (see [CORS documentation](cors.md))
4. **Use httpOnly and secure flags** on sensitive cookies
5. **Headers are case-insensitive** but constants use standard casing

### Performance

1. **Cache parsed headers** if accessing multiple times (they use lazy evaluation)
2. **Consider interceptor order** - put cheaper checks first
3. **Set appropriate timeout values** for long-running handlers
4. **Use streaming** for large responses instead of loading into memory
5. **Validate early** to avoid expensive operations on bad requests

### Security

1. **Never trust client input** - validate and sanitize all request data
2. **Use HTTPS in production** - check `request.protocol == "https"`
3. **Implement rate limiting** via interceptors
4. **Log security events** - failed auth attempts, suspicious patterns
5. **Use secure cookie settings** - httpOnly, secure, SameSite=Strict

## Testing Endpoints

Store endpoint references for easy testing:

```kotlin
val getUser = path.path("users").arg<User.ID>("id").get bind HttpHandler { request ->
    val user = userTable().get(request.path.arg1) ?: throw NotFoundException()
    HttpResponse(body = user.toTypedData(request.headers.accept))
}

// In tests:
@Test
fun testGetUser() = Server.testBlocking(settings = { database set Database.Settings("ram") }) {
    val user = User(email = "test@example.com")
    execute { userTable().insertOne(user) }  // Direct database access needs a ServerRuntime

    val response = Server.getUser.test(user._id)  // Path arguments are passed positionally
    assertEquals(HttpStatus.OK, response.status)
}
```

See the [Testing Guide](../docs-guide/guide/testing.md) for more endpoint testing patterns.

## See Also

- [Typed Endpoints](typed-endpoints.md) - Type-safe API endpoints with auto-generated documentation
- [CORS](cors.md) - Cross-origin resource sharing configuration
- [Authentication](authentication.md) - Request authentication and authorization
- [WebSockets](websockets.md) - Real-time bidirectional communication
- [Files](files.md) - File upload and download handling
