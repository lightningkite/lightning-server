# Package com.lightningkite.lightningserver.http

Core HTTP types and utilities for Lightning Server applications.

## Overview

This package provides the fundamental building blocks for HTTP request/response handling in Lightning Server. It includes types for requests, responses, headers, status codes, URL parsing, and middleware support.

## Key Components

### Request/Response Types

- **[HttpRequest](HttpRequest.kt)** - Represents an incoming HTTP request with path, headers, body, and metadata
- **[HttpResponse](HttpResponse.kt)** - Represents an HTTP response with body, status code, and headers
- **[HttpStatus](HttpStatus.kt)** - Type-safe HTTP status codes (200 OK, 404 Not Found, etc.)

### Headers

- **[HttpHeaders](HttpHeaders.kt)** - Immutable HTTP headers collection with builder pattern and convenience accessors
- **[HttpHeader](HttpHeader.kt)** - Constants for standard HTTP header names
- **[HttpHeaderValue](HttpHeaderValue.kt)** - Parsed header value with root value and parameters

### Handlers and Middleware

- **[HttpHandler](HttpHandler.kt)** - Interface for handling HTTP requests and producing responses
- **[HttpInterceptor](HttpInterceptor.kt)** - Middleware for intercepting and modifying requests/responses
- **[HttpEndpoint](HttpEndpoint.kt)** - Combines a path specification with an HTTP method

### Exception Handling

- **[ExceptionHttpHandler](ExceptionHttpHandler.kt)** - Interface for converting exceptions to HTTP responses
- **[DefaultExceptionHttpHandler](DefaultExceptionHttpHandler.kt)** - Default implementation that handles HttpStatusException and generic errors

### URL Parsing

- **[parse.kt](parse.kt)** - URL parsing utilities:
  - **PathSegments** - Parsed URL path with automatic URL decoding
  - **QueryParameters** - Parsed query string parameters with URL decoding
  - **PathAndParams** - Combined path and query parameters

## Usage Examples

### Basic Request Handler

```kotlin
val endpoint = path.path("users").arg<String>("id").get bind HttpHandler { request ->
    val userId = request.path.arg1
    val user = database().table<User>().get(userId)
    HttpResponse.json(user)
}
```

### Headers and Cookies

```kotlin
val response = HttpResponse(
    body = TypedData.json(data),
    status = HttpStatus.OK,
    headers = HttpHeaders {
        set("Content-Type", "application/json")
        setCookie(
            key = "session",
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
val loggingInterceptor = HttpInterceptor { request, cont ->
    val start = Clock.System.now()
    val response = cont(request)
    val duration = Clock.System.now() - start
    println("${request.path} completed in ${duration.inWholeMilliseconds}ms")
    response
}

object Server : ServerBuilder() {
    init {
        install(loggingInterceptor)
    }
}
```

### Query Parameters

```kotlin
val endpoint = path.path("search").get bind HttpHandler { request ->
    val query = request.queryParameters["q"] ?: ""
    val page = request.queryParameters["page"]?.toIntOrNull() ?: 1

    val results = searchService.search(query, page)
    HttpResponse.json(results)
}
```

## Architecture Notes

### Immutability

Most types in this package are immutable for thread safety:
- `HttpHeaders` - Use builder or `copy()` for modifications
- `HttpStatus` - Immutable value class
- `PathSegments`, `QueryParameters` - Immutable value classes

### Context Receivers

Handler and interceptor interfaces use Kotlin context receivers for dependency injection:

```kotlin
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

### Authentication via Interceptor

```kotlin
val authInterceptor = HttpInterceptor { request, cont ->
    val token = request.headers[HttpHeader.Authorization]?.root
        ?: throw UnauthorizedException("Missing token")

    val user = validateToken(token)
    val requestWithUser = request.copy(
        cache = request.cache.apply { put("user", user) }
    )
    cont(requestWithUser)
}
```

### Custom Error Responses

```kotlin
object MyExceptionHandler : ExceptionHttpHandler {
    context(server: ServerRuntime)
    override suspend fun handle(
        request: HttpRequest<PathSpec>,
        exception: Exception
    ): HttpResponse = when (exception) {
        is ValidationException -> HttpResponse(
            status = HttpStatus.BadRequest,
            body = TypedData.json(exception.errors)
        )
        else -> DefaultExceptionHttpHandler.handle(request, exception)
    }
}
```

### Content Negotiation

```kotlin
val endpoint = path.path("data").get bind HttpHandler { request ->
    val data = getData()

    val acceptsJson = request.headers.accept.any { it.subtype == "json" }
    if (acceptsJson) {
        HttpResponse.json(data)
    } else {
        HttpResponse(
            body = TypedData.xml(data),
            headers = HttpHeaders("Content-Type" to "application/xml")
        )
    }
}
```

## See Also

- **[HTTP Core Documentation](../../../../docs/http-core.md)** - Comprehensive guide to HTTP core types
- **[Endpoints Documentation](../../../../docs/endpoints.md)** - Defining and routing endpoints
- **[Typed Endpoints](../../../../docs/typed-endpoints.md)** - Type-safe API endpoint definitions
- **[CORS](../cors/)** - Cross-origin resource sharing implementation

## Testing

Unit tests for this package can be found at:
- `core/src/test/kotlin/com/lightningkite/lightningserver/http/HttpTest.kt`

These tests cover:
- HttpStatus success detection and string formatting
- HttpHeaders case-insensitive access and builder pattern
- HttpHeaderValue parsing for standard headers and cookies
- PathSegments and QueryParameters URL encoding/decoding
- PathAndParams combined parsing

## Notes

- All header names are normalized to lowercase internally for case-insensitive comparison
- Query parameter parsing currently requires values (keys without values will throw exception)
- The `pathHack()` function in QueryParameters is a temporary workaround for WebSocket auth
- HttpHeader constants include both standard and common non-standard headers
