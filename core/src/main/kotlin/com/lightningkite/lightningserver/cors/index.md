# CORS Package

Package: `com.lightningkite.lightningserver.cors`

## Overview

This package provides Cross-Origin Resource Sharing (CORS) support for Lightning Server applications. CORS is a browser security mechanism that controls which web applications can make cross-origin requests to your API.

## Files

### CorsSettings.kt

**Purpose:** Configuration data class for CORS behavior.

**Key Components:**
- `CorsSettings` - Serializable data class containing all CORS configuration options
- `CorsSettings.allowAll` - Companion object preset for permissive development settings

**Usage:**
```kotlin
val corsSettings = setting("cors", CorsSettings(
    limitToDomains = listOf("https://example.com"),
    limitToHeaders = listOf("Content-Type", "Authorization"),
    limitToMethods = listOf("GET", "POST", "PUT", "DELETE"),
    allowCredentials = true
))
```

### CorsInterceptor.kt

**Purpose:** HTTP and WebSocket interceptor that enforces CORS policies.

**Key Components:**
- `CorsInterceptor` - Main interceptor class that implements `HttpInterceptor` and `WebSocketHandlerInterceptor`
- `originMatches()` - Internal function for pattern matching origins against allowed patterns

**Features:**
- Automatic preflight (OPTIONS) request handling
- Origin validation with wildcard support
- CORS header injection for allowed origins
- WebSocket origin enforcement

**Usage:**
```kotlin
object Server : ServerBuilder() {
    val corsSettings = setting("cors", CorsSettings(...))

    init {
        interceptors.add(CorsInterceptor(corsSettings))
    }
}
```

**How It Works:**
1. Checks incoming requests for `Origin` header
2. Validates origin against configured patterns
3. For preflight (OPTIONS) requests:
   - Discovers available methods for the path
   - Returns 204 with appropriate CORS headers
4. For regular requests:
   - Adds CORS headers if origin is allowed
   - Optionally blocks disallowed origins
5. For WebSocket connections:
   - Validates origin during handshake
   - Always rejects disallowed origins

## Pattern Matching

The package supports flexible origin pattern matching:

| Pattern | Example | Matches |
|---------|---------|---------|
| Exact with scheme | `https://example.com` | Only `https://example.com` |
| Domain only | `example.com` | Any scheme: `https://example.com`, `http://example.com` |
| Subdomain wildcard | `https://*.example.com` | `https://sub.example.com`, `https://deep.sub.example.com` |
| Subdomain wildcard (no scheme) | `*.example.com` | Any subdomain with any scheme |
| Universal wildcard | `*` | All origins (not recommended) |

## Security Considerations

1. **Never use wildcards in production** - Always specify exact origins
2. **Credentials require exact origins** - Can't use `*` when `allowCredentials = true`
3. **WebSockets always enforce origin** - Regardless of `forbidOnMatchFail` setting
4. **Minimize exposed headers** - Only expose what clients need
5. **Use HTTPS in production** - Especially with credentials

## Testing

Tests are located in `core/src/test/kotlin/com/lightningkite/lightningserver/cors/`:

- `OriginMatchesTest.kt` - Unit tests for origin pattern matching logic
- `CorsInterceptorTest.kt` - Integration tests for CORS behavior

Run tests with:
```bash
./gradlew :core:test --tests "com.lightningkite.lightningserver.cors.*"
```

## Related Documentation

- [CORS Documentation](../../../../../../../../../docs/cors.md) - Complete guide to using CORS
- [Endpoints Documentation](../../../../../../../../../docs/endpoints.md) - HTTP endpoint definition
- [WebSockets Documentation](../../../../../../../../../docs/websockets.md) - WebSocket configuration

## Common Issues

**"No CORS headers in response"**
- Check that request includes `Origin` header
- Verify origin matches a pattern in `limitToDomains`
- Confirm `CorsInterceptor` is added to `interceptors`

**"Preflight request returns 404"**
- No endpoint is defined for that path
- Check your routing configuration

**"WebSocket connection refused"**
- Origin doesn't match any `limitToDomains` pattern
- WebSocket connections always enforce origin matching

**"Request works in Postman but fails in browser"**
- Postman doesn't enforce CORS (it's not a browser)
- Browsers strictly enforce CORS policy
- Verify your CORS configuration

## Future Improvements

See TODO comments in source files for potential API improvements:
- Better separation of mirroring vs blocking behavior
- Validation functions for incompatible configurations
- Additional preset factory methods for common scenarios
- Public origin matching function for custom logic
- Case-insensitive domain matching
