# Package com.lightningkite.lightningserver.http

Core HTTP types and utilities for Lightning Server applications.

## Overview

This package provides the fundamental building blocks for HTTP request/response handling in Lightning Server. It
includes types for requests, responses, headers, status codes, URL parsing, and middleware support.

## Key Components

### Request/Response Types

- **[HttpRequest](HttpRequest.kt)** - Represents an incoming HTTP request with path, headers, body, and metadata
- **[HttpResponse](HttpResponse.kt)** - Represents an HTTP response with body, status code, and headers
- **[HttpStatus](HttpStatus.kt)** - Type-safe HTTP status codes (200 OK, 404 Not Found, etc.)
- **[RequestIdentity](RequestIdentity.kt)** - How an engine identifies a request: a server-controlled id, adopted from a
  trusted reverse proxy header only when one is configured

### Headers

- **[HttpHeaders](HttpHeaders.kt)** - Immutable HTTP headers collection with builder pattern and convenience accessors
- **[HttpHeader](HttpHeader.kt)** - Constants for standard HTTP header names
- **[HttpHeaderValue](HttpHeaderValue.kt)** - Parsed header value with root value and parameters

### Handlers and Middleware

- **[HttpHandler](HttpHandler.kt)** - Interface for handling HTTP requests and producing responses. Its `handle`
  method is `@OverrideOnly`: implement it, but run a handler with `handleWithMetrics` so the call is its own execution
  and passes through the server's interceptors
- **[HttpInterceptor](HttpInterceptor.kt)** - Middleware for intercepting and modifying requests/responses
- **[SecurityHeadersInterceptor](SecurityHeadersInterceptor.kt)** - Adds `X-Content-Type-Options` and, over https,
  `Strict-Transport-Security`
- **[HttpEndpoint](HttpEndpoint.kt)** - Combines a path specification with an HTTP method

### Exception Handling

- **[HttpExceptionHandler](HttpExceptionHandler.kt)** - Interface for converting exceptions to HTTP responses.
  `HttpExceptionHandler.Default` maps `HttpStatusException` to its status and anything else to a 500

### URL Parsing

- **[parse.kt](parse.kt)** - URL parsing utilities:
    - **PathSegments** - Parsed URL path with automatic URL decoding
    - **QueryParameters** - Parsed query string parameters with URL decoding
    - **PathAndParams** - Combined path and query parameters

## Usage Examples

### Basic Request Handler

```kotlin
// registerTable defines the table, registers it, and creates its once-per-deploy prepare
// task.  Declare it once on your ServerBuilder; invoke it to get the live table.
val userTable = database.registerTable<User>("User")

val endpoint = path.path("users").arg<User.ID>("id").get bind HttpHandler { request ->
    val user = userTable().get(request.path.arg1) ?: throw NotFoundException()
    // Encodes for whatever the client accepts; the server must call registerBasicMediaTypeCoders()
    HttpResponse(body = user.toTypedData(request.headers.accept))
}
```

For JSON APIs, prefer typed endpoints, which handle encoding, documentation, and SDK generation for you.

### Headers and Cookies

```kotlin
val response = HttpResponse(
    body = TypedData.text("OK", MediaType.Text.Plain),
    status = HttpStatus.OK,
    headers = HttpHeaders {
        add(HttpHeader.CacheControl, "no-store")
        setCookie(
            name = "session",
            value = "token123",
            httpOnly = true,
            secure = true,
            sameSite = HttpHeaders.SameSite.Strict
        )
    }
)
```

### Interceptor Middleware

```kotlin
object LoggingInterceptor : HttpInterceptor {
    context(runtime: ServerRuntime)
    override suspend fun <PATH : PathSpec> intercept(
        request: HttpRequest<PATH>,
        cont: suspend context(ServerRuntime) (HttpRequest<PATH>) -> HttpResponse,
    ): HttpResponse {
        val start = TimeSource.Monotonic.markNow()
        val response = cont(request)
        println("${request.path} completed in ${start.elapsedNow().inWholeMilliseconds}ms")
        return response
    }
}

object Server : ServerBuilder() {
    init {
        install(LoggingInterceptor)
    }
}
```

Interceptors run once for each request the engine receives, and see the raw request and response. They do not run for
requests dispatched inside another execution, such as each part of a `/meta/bulk` call or an internal endpoint call. A
concern that must cover those belongs in an `ExecutionInterceptor`.

### Query Parameters

```kotlin
val endpoint = path.path("search").get bind HttpHandler { request ->
    val query = request.queryParameters["q"] ?: ""
    val page = request.queryParameters["page"]?.toIntOrNull() ?: 1

    val results = searchService.search(query, page)
    HttpResponse(body = results.toTypedData(request.headers.accept))
}
```

## Architecture Notes

### Immutability

Most types in this package are immutable for thread safety:

- `HttpHeaders` - Use builder or `copy()` for modifications
- `HttpStatus` - Immutable value class
- `PathSegments`, `QueryParameters` - Immutable value classes

### Context Parameters

Handler and interceptor interfaces use Kotlin context parameters to reach the current `ServerRuntime`:

```kotlin
@OverrideOnly
context(server: ServerRuntime)
suspend fun handle(request: HttpRequest<PATH>): HttpResponse
```

This provides access to server settings, databases, caches, and other services without explicit parameter passing.

### Type Safety

Path arguments are type-safe through the PathSpec generic system:

```kotlin
// PathSpec2<String, Int> means two arguments: String, then Int
val endpoint = path.path("users").arg<String>("userId")
    .path("posts").arg<Int>("postId").get

// In handler:
val userId: String = request.path.arg1  // Typed as String
val postId: Int = request.path.arg2     // Typed as Int
```

### URL Encoding

All URL parsing utilities automatically handle URL encoding/decoding:

- `PathSegments.parse()` - Decodes path segments
- `QueryParameters.parse()` - Decodes parameter keys and values
- `toString()` methods - Encode output appropriately

### Performance Considerations

1. **Header Parsing** - Convenience properties (contentType, contentLength) use lazy evaluation
2. **Interceptor Chaining** - Compiled once at startup for efficient execution
3. **Value Classes** - PathSegments and QueryParameters use value classes to avoid allocation overhead

## Common Patterns

### Error Responses

Throw an `HttpStatusException` subclass, such as `BadRequestException`, `UnauthorizedException`, or
`NotFoundException`, and the server's exception handler turns it into a response with that status:

```kotlin
val endpoint = path.path("items").arg<Int>("id").get bind HttpHandler { request ->
    if (request.path.arg1 < 0) throw BadRequestException("The id must not be negative")
    // ...
}
```

Exceptions are mapped to responses inside the interceptor chain, so interceptors such as CORS still see and
post-process error responses. There is currently no registration API for a custom `HttpExceptionHandler`; every server
uses `HttpExceptionHandler.Default`.

### Content Negotiation

```kotlin
val endpoint = path.path("data").get bind HttpHandler { request ->
    val data = getData()
    // Picks a registered encoder from the request's Accept header, falling back to the default
    HttpResponse(body = data.toTypedData(request.headers.accept))
}
```

## See Also

- **[Endpoints Documentation](../../../../../../../../docs/endpoints.md)** - Defining and routing endpoints
- **[Typed Endpoints](../../../../../../../../docs/typed-endpoints.md)** - Type-safe API endpoint definitions
- **[CORS](../cors/)** - Cross-origin resource sharing implementation

## Testing

Unit tests for this package can be found in `core/src/test/kotlin/com/lightningkite/lightningserver/http/`.

These tests cover:

- HttpStatus success detection and string formatting
- HttpHeaders case-insensitive access and builder pattern
- HttpHeaderValue parsing for standard headers and cookies
- PathSegments and QueryParameters URL encoding/decoding
- PathAndParams combined parsing
- Interceptor ordering, the default exception handler, and request identity

## Notes

- All header names are normalized to lowercase internally for case-insensitive comparison
- HttpHeader constants include both standard and common non-standard headers
