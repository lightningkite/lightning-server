# CORS (Cross-Origin Resource Sharing)

Last updated January 2025 (`version-5`)

## Overview

Cross-Origin Resource Sharing (CORS) is a security mechanism that allows your Lightning Server application to safely
handle requests from web applications hosted on different domains. Without CORS configuration, browsers will block
cross-origin requests by default.

Lightning Server provides a `CorsInterceptor` that handles all CORS logic, including:

- Origin validation with flexible pattern matching
- Automatic preflight (OPTIONS) request handling
- CORS header injection
- WebSocket connection validation

## Quick Start

The simplest way to enable CORS is to add the `CorsInterceptor` to your server:

```kotlin
object Server : ServerBuilder() {
    val corsSettings = setting("cors", CorsSettings(
        limitToDomains = listOf("https://example.com"),
        limitToHeaders = listOf("Content-Type", "Authorization"),
        limitToMethods = listOf("GET", "POST", "PUT", "DELETE"),
        allowCredentials = true
    ))

    init {
        interceptors.add(CorsInterceptor(corsSettings))
    }

    val myEndpoint = path.path("api").get bind HttpHandler {
        HttpResponse.plainText("Hello from API!")
    }
}
```

## Configuration Options

### limitToDomains

Controls which origins are allowed to make cross-origin requests.

```kotlin
// Allow specific origins
limitToDomains = listOf("https://example.com", "https://app.example.com")

// Allow all subdomains
limitToDomains = listOf("https://*.example.com")

// Allow any scheme (http or https)
limitToDomains = listOf("*.example.com")

// Allow all origins (development only!)
limitToDomains = listOf("*")
```

**Pattern Matching:**

- `https://example.com` - Exact match with scheme
- `*.example.com` - Matches any subdomain with any scheme
- `https://*.example.com` - Matches any subdomain with HTTPS only
- `*` - Matches all origins (not recommended for production)

### limitToHeaders

Controls which HTTP headers can be sent in requests.

```kotlin
// Allow specific headers
limitToHeaders = listOf("Content-Type", "Authorization", "X-Api-Key")

// Mirror all requested headers (permissive)
limitToHeaders = listOf("*")

// Block additional headers
limitToHeaders = emptyList()
```

### limitToMethods

Controls which HTTP methods are allowed for cross-origin requests.

```kotlin
// Allow specific methods
limitToMethods = listOf("GET", "POST", "PUT", "DELETE")

// Mirror requested methods (permissive)
limitToMethods = listOf("*")

// Only allow safe methods
limitToMethods = listOf("GET", "HEAD")
```

### exposedHeaders

Specifies which response headers should be exposed to the client JavaScript.

```kotlin
// Expose custom headers
exposedHeaders = listOf("X-Total-Count", "X-Page-Number")

// Don't expose any additional headers
exposedHeaders = emptyList()
```

By default, browsers only expose "CORS-safe" response headers. Use this to expose additional headers.

### allowCredentials

Controls whether the browser should include credentials (cookies, authorization headers) in requests.

```kotlin
// Allow credentials
allowCredentials = true

// Block credentials
allowCredentials = false
```

**Warning:** When `allowCredentials = true`, you cannot use wildcard (`*`) for `limitToDomains`. You must specify exact
origins or patterns.     <!-TODO-------------------------->

### cacheLength

How long (in seconds) browsers should cache preflight responses.

```kotlin
// Cache for 1 hour
cacheLength = 3600u

// Cache for 24 hours
cacheLength = 86400u

// Don't send cache header
cacheLength = null
```

### forbidOnMatchFail

Controls what happens when a request has a non-matching origin.

```kotlin
// Block disallowed origins with 403 Forbidden
forbidOnMatchFail = true

// Allow requests but omit CORS headers
forbidOnMatchFail = false
```

When `false`, the request proceeds normally but without CORS headers, meaning the browser will block the response. This
can be useful to avoid revealing endpoint existence.

**Note:** WebSocket connections always fail on origin mismatch, regardless of this setting.

## Common Configurations

### Development (Permissive)

```kotlin
val corsSettings = setting("cors", CorsSettings.forDevelopment())
```

This uses the built-in `forDevelopment` preset that mirrors all origins, headers, and methods. **Never use this in
production.**

### Production (Single Origin)

```kotlin
val corsSettings = setting("cors", CorsSettings(
    limitToDomains = listOf("https://app.example.com"),
    limitToHeaders = listOf("Content-Type", "Authorization"),
    limitToMethods = listOf("GET", "POST", "PUT", "DELETE"),
    allowCredentials = true,
    cacheLength = 3600u,
    forbidOnMatchFail = true
))
```

### Production (Multiple Subdomains)

```kotlin
val corsSettings = setting("cors", CorsSettings(
    limitToDomains = listOf("https://*.example.com"),
    limitToHeaders = listOf("Content-Type", "Authorization"),
    limitToMethods = listOf("GET", "POST", "PUT", "DELETE", "PATCH"),
    allowCredentials = true,
    cacheLength = 86400u,
    forbidOnMatchFail = true
))
```

## How Preflight Requests Work

When a browser makes a "complex" cross-origin request (with custom headers, methods other than GET/POST, etc.), it first
sends a preflight OPTIONS request.

The `CorsInterceptor` automatically handles preflight requests:

1. Discovers which HTTP methods are actually defined for the requested path
2. Filters methods based on `limitToMethods` if configured
3. Returns a 204 No Content response with appropriate CORS headers
4. Returns 404 if no methods are defined for that path

Example preflight flow:

```
Browser → OPTIONS /api/users
          Origin: https://example.com
          Access-Control-Request-Method: POST
          Access-Control-Request-Headers: Content-Type

Server →  204 No Content
          Access-Control-Allow-Origin: https://example.com
          Access-Control-Allow-Methods: GET,POST,PUT,DELETE
          Access-Control-Allow-Headers: Content-Type
          Access-Control-Max-Age: 3600
          Access-Control-Allow-Credentials: true
```

Then the actual request:

```
Browser → POST /api/users
          Origin: https://example.com
          Content-Type: application/json

Server →  200 OK
          Access-Control-Allow-Origin: https://example.com
          Access-Control-Allow-Credentials: true
          Access-Control-Expose-Headers: X-Total-Count
```

## WebSocket CORS

WebSocket connections are also protected by CORS:

```kotlin
val wsEndpoint = path.path("chat") bind WebSocketHandler(
    storageSerializer = Unit.serializer(),
    willConnect = { Unit },
    messageFromClient = { frame ->
        send(frame) // Echo
    }
)
```

The `CorsInterceptor` validates the `Origin` header during the WebSocket handshake. If the origin doesn't match, the
connection is rejected with 403 Forbidden.

**Important:** WebSocket connections always enforce origin checking, even if `forbidOnMatchFail = false`. This is
because browsers themselves do not verify CORS for WebSocket connections.

## Settings File Configuration

CORS settings can be configured via your `settings.json` file:

```json
{
  "cors": {
    "limitToDomains": ["https://app.example.com", "https://*.trusted.com"],
    "limitToHeaders": ["Content-Type", "Authorization"],
    "limitToMethods": ["GET", "POST", "PUT", "DELETE"],
    "exposedHeaders": ["X-Total-Count"],
    "allowCredentials": true,
    "cacheLength": 3600,
    "forbidOnMatchFail": true
  }
}
```

## Testing CORS

You can test CORS behavior using curl:

```bash
# Test a regular request with Origin
curl -H "Origin: https://example.com" \
     http://localhost:8080/api/data

# Test a preflight request
curl -X OPTIONS \
     -H "Origin: https://example.com" \
     -H "Access-Control-Request-Method: POST" \
     -H "Access-Control-Request-Headers: Content-Type" \
     http://localhost:8080/api/data
```

In your tests, use the test utilities:

```kotlin
@Test
fun testCors() {
    TestServer.test(
        settings = { generalSettings set GeneralServerSettings() }
    ) {
        runBlocking {
            val response = myEndpoint.test(
                headers = HttpHeaders {
                    set(HttpHeader.Origin, "https://example.com")
                }
            )
            assertEquals("https://example.com",
                response.headers[HttpHeader.AccessControlAllowOrigin]?.root)
        }
    }
}
```

## Security Best Practices

### 1. Never Use Wildcards in Production

```kotlin
// BAD - allows any origin
limitToDomains = listOf("*")

// GOOD - specific origins only
limitToDomains = listOf("https://app.example.com")
```

### 2. Be Careful with Credentials

When `allowCredentials = true`, browsers include cookies and authorization headers. Make sure:

- You specify exact origins (no wildcards)
- You validate authentication properly
- You use HTTPS in production

### 3. Minimize Exposed Headers

Only expose headers that the client actually needs:

```kotlin
// Only expose what's necessary
exposedHeaders = listOf("X-Total-Count")

// Don't expose sensitive information
// exposedHeaders = listOf("X-Internal-Id", "X-Db-Query-Time") // BAD
```

### 4. Use forbidOnMatchFail = true in Production

```kotlin
forbidOnMatchFail = true  // Reject invalid origins immediately
```

This prevents your API from processing requests from unknown origins.

### 5. Consider Subdomain Wildcards Carefully

```kotlin
// This allows ANY subdomain
limitToDomains = listOf("https://*.example.com")
```

Make sure you control all subdomains, or attackers could register malicious subdomains.

## Troubleshooting

### Browser shows "CORS error" but server logs nothing

This usually means the preflight request failed. Check that your `limitToHeaders` and `limitToMethods` include what the
browser is requesting.

### Request works in Postman but fails in browser

Postman doesn't enforce CORS. Browsers do. Make sure your origin is in `limitToDomains`.

### Credentials not being sent

Check that:

1. `allowCredentials = true` in your CORS settings
2. Your frontend code uses `credentials: 'include'` (fetch) or `withCredentials: true` (axios)
3. Your origin is exact (not a wildcard)

### WebSocket connection refused

WebSocket connections require matching origins. Make sure the origin matches one of your `limitToDomains` patterns.

### OPTIONS requests return 404

This means no endpoint is defined for that path. Check your routing configuration.

## Related Topics

- [Endpoints](endpoints.md) - How to define HTTP endpoints
- [WebSockets](websockets.md) - WebSocket configuration
- [Settings](settings.md) - Server settings management
- [Authentication](authentication.md) - User authentication

## See Also

- Package: `com.lightningkite.lightningserver.cors`
- Classes: `CorsInterceptor`, `CorsSettings`
- [MDN CORS Documentation](https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS)
