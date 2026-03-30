# Complete Plan: Per-Endpoint Rate Limiting for Lightning Server

Based on analysis of the Lightning Server architecture, this document provides a comprehensive implementation plan for rate limiting at the individual HTTP endpoint level.

## 1. Architecture Overview

The implementation will follow Lightning Server's architectural patterns:

- **Modular design** with paired JVM + shared multiplatform modules
- **Service abstraction** using the existing `Cache` abstraction for counter storage
- **Interceptor-based** cross-cutting concern implementation
- **Declarative configuration** at the endpoint level
- **Type-safe API** with compile-time guarantees

## 2. Module Structure

Create two new modules following the framework convention:

### **Module: `ratelimit`** (JVM)
- Location: `/ratelimit/`
- Dependencies: `core`, `ratelimit-shared`, `service-abstractions`
- Contains: Interceptor implementation, cache-based counter logic, JVM-specific utilities

### **Module: `ratelimit-shared`** (Multiplatform)
- Location: `/ratelimit-shared/`
- Dependencies: `core-shared`
- Contains: Configuration classes, rate limit metadata, shared types

Update `settings.gradle.kts`:
```kotlin
include(":ratelimit")
include(":ratelimit-shared")
```

## 3. Core Components Design

### 3.1 Rate Limit Configuration (`ratelimit-shared`)

**File: `ratelimit-shared/src/commonMain/kotlin/com/lightningkite/lightningserver/ratelimit/RateLimitConfig.kt`**

```kotlin
@Serializable
data class RateLimitConfig(
    val requests: Int,
    val window: Duration,
    val keyStrategy: KeyStrategy = KeyStrategy.IP,
    val scope: String? = null,
) {
    enum class KeyStrategy {
        IP,              // Rate limit by source IP
        USER,            // Rate limit by authenticated user ID
        IP_AND_USER,     // Both combined
        CUSTOM,          // Custom key from request
        GLOBAL           // Single shared limit
    }
}

@Serializable
data class RateLimitSettings(
    val enabled: Boolean = true,
    val defaultLimit: RateLimitConfig? = null,
    val headerPrefix: String = "X-RateLimit-",
)
```

### 3.2 Per-Endpoint Rate Limit Metadata

**File: `ratelimit-shared/src/commonMain/kotlin/com/lightningkite/lightningserver/ratelimit/RateLimitMetadata.kt`**

```kotlin
/**
 * Metadata attached to endpoints for rate limiting configuration
 */
data class RateLimitMetadata(
    val config: RateLimitConfig,
    val customKeyExtractor: (suspend (HttpRequest<*>) -> String)? = null
)

/**
 * Extension key for storing rate limit metadata in endpoint extensions
 */
object RateLimitMetadataKey : Extensions.Key<RateLimitMetadata>
```

### 3.3 Fluent API for Endpoint Configuration

**File: `ratelimit/src/main/kotlin/com/lightningkite/lightningserver/ratelimit/RateLimitExtensions.kt`**

```kotlin
/**
 * Attach rate limiting to an endpoint
 */
fun <PATH : PathSpec> HttpEndpoint<PATH>.rateLimit(
    requests: Int,
    window: Duration,
    keyStrategy: KeyStrategy = KeyStrategy.IP,
    scope: String? = null,
    customKeyExtractor: (suspend (HttpRequest<*>) -> String)? = null
): HttpEndpoint<PATH> {
    val metadata = RateLimitMetadata(
        config = RateLimitConfig(requests, window, keyStrategy, scope),
        customKeyExtractor = customKeyExtractor
    )
    return this.copy(
        extensions = extensions.plus(RateLimitMetadataKey, metadata)
    )
}

/**
 * Attach rate limiting with a pre-built config
 */
fun <PATH : PathSpec> HttpEndpoint<PATH>.rateLimit(
    config: RateLimitConfig,
    customKeyExtractor: (suspend (HttpRequest<*>) -> String)? = null
): HttpEndpoint<PATH> {
    val metadata = RateLimitMetadata(config, customKeyExtractor)
    return this.copy(
        extensions = extensions.plus(RateLimitMetadataKey, metadata)
    )
}
```

### 3.4 Rate Limiting Interceptor

**File: `ratelimit/src/main/kotlin/com/lightningkite/lightningserver/ratelimit/RateLimitInterceptor.kt`**

```kotlin
class RateLimitInterceptor(
    private val settings: Runtime<RateLimitSettings>,
    private val cache: Runtime<Cache>,
) : HttpInterceptor {

    override val name: String = "RateLimit"

    context(runtime: ServerRuntime)
    override suspend fun intercept(
        request: HttpRequest<*>,
        cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse
    ): HttpResponse {
        if (!settings().enabled) {
            return cont(request)
        }

        // Check if endpoint has rate limit metadata
        val metadata = request.path.match.value.extensions[RateLimitMetadataKey]
            ?: settings().defaultLimit?.let { RateLimitMetadata(it) }

        if (metadata == null) {
            return cont(request)  // No rate limiting configured
        }

        // Generate rate limit key
        val key = generateKey(request, metadata, runtime)

        // Check and update rate limit using sliding window algorithm
        val result = checkRateLimit(
            cacheService = cache(),
            key = key,
            config = metadata.config,
            now = runtime.clock.now()
        )

        // Add rate limit headers to response
        val response = if (result.allowed) {
            cont(request)
        } else {
            HttpResponse(
                status = HttpStatus.TooManyRequests,
                body = TypedData.text("Rate limit exceeded. Retry after ${result.retryAfter}"),
                headers = HttpHeaders.EMPTY
            )
        }

        return response.copy(
            headers = response.headers.copy {
                val prefix = settings().headerPrefix
                add("${prefix}Limit", metadata.config.requests.toString())
                add("${prefix}Remaining", result.remaining.toString())
                add("${prefix}Reset", result.resetAt.epochSeconds.toString())
                if (!result.allowed) {
                    add(HttpHeader.RetryAfter, result.retryAfter.inWholeSeconds.toString())
                }
            }
        )
    }

    context(runtime: ServerRuntime)
    private suspend fun generateKey(
        request: HttpRequest<*>,
        metadata: RateLimitMetadata,
        runtime: ServerRuntime
    ): String {
        val config = metadata.config
        val endpoint = request.path.endpoint.path.toString()
        val scope = config.scope ?: endpoint

        val keyPart = when (config.keyStrategy) {
            KeyStrategy.GLOBAL -> "global"
            KeyStrategy.IP -> request.sourceIp
            KeyStrategy.USER -> {
                // Extract user ID from auth (requires auth to be cached)
                val userId = extractUserId(request)
                    ?: throw UnauthorizedException("Rate limiting by user requires authentication")
                userId
            }
            KeyStrategy.IP_AND_USER -> {
                val userId = extractUserId(request) ?: "anonymous"
                "${request.sourceIp}:$userId"
            }
            KeyStrategy.CUSTOM -> {
                metadata.customKeyExtractor?.invoke(request)
                    ?: throw IllegalStateException("Custom key strategy requires customKeyExtractor")
            }
        }

        return "ratelimit:$scope:$keyPart"
    }

    private fun extractUserId(request: HttpRequest<*>): String? {
        // Access authentication from request cache if available
        // This works with Lightning Server's auth pattern
        return request.cache[authCacheKey]?.subjectId?.toString()
    }
}
```

### 3.5 Rate Limiting Algorithm (Sliding Window)

**File: `ratelimit/src/main/kotlin/com/lightningkite/lightningserver/ratelimit/SlidingWindowRateLimit.kt`**

```kotlin
data class RateLimitResult(
    val allowed: Boolean,
    val remaining: Int,
    val resetAt: Instant,
    val retryAfter: Duration
)

/**
 * Implements sliding window rate limiting using cache operations
 */
suspend fun checkRateLimit(
    cacheService: Cache,
    key: String,
    config: RateLimitConfig,
    now: Instant
): RateLimitResult {
    val windowKey = "$key:${now.epochSeconds / config.window.inWholeSeconds}"
    val windowStart = Instant.fromEpochSeconds(
        (now.epochSeconds / config.window.inWholeSeconds) * config.window.inWholeSeconds
    )
    val resetAt = windowStart + config.window

    // Atomic increment with expiration
    val count = cacheService.add(windowKey, 1, config.window)
        ?: cacheService.increment(windowKey, 1).also {
            // Set expiration if key existed but had no TTL
            cacheService.setExpiration(windowKey, config.window)
        }

    val allowed = count <= config.requests
    val remaining = maxOf(0, config.requests - count)
    val retryAfter = if (allowed) Duration.ZERO else resetAt - now

    return RateLimitResult(
        allowed = allowed,
        remaining = remaining,
        resetAt = resetAt,
        retryAfter = retryAfter
    )
}
```

Alternative: **Token Bucket Algorithm** (more sophisticated)

```kotlin
/**
 * Token bucket algorithm for smoother rate limiting
 */
@Serializable
data class TokenBucket(
    val tokens: Double,
    val lastRefill: Long  // Epoch seconds
)

suspend fun checkRateLimitTokenBucket(
    cacheService: Cache,
    key: String,
    config: RateLimitConfig,
    now: Instant
): RateLimitResult {
    val bucketKey = "$key:bucket"

    // Retrieve or initialize bucket
    var bucket = cacheService.get<TokenBucket>(bucketKey) ?: TokenBucket(
        tokens = config.requests.toDouble(),
        lastRefill = now.epochSeconds
    )

    // Refill tokens based on time passed
    val refillRate = config.requests.toDouble() / config.window.inWholeSeconds
    val secondsPassed = now.epochSeconds - bucket.lastRefill
    val tokensToAdd = secondsPassed * refillRate

    bucket = bucket.copy(
        tokens = minOf(config.requests.toDouble(), bucket.tokens + tokensToAdd),
        lastRefill = now.epochSeconds
    )

    // Try to consume a token
    val allowed = bucket.tokens >= 1.0
    if (allowed) {
        bucket = bucket.copy(tokens = bucket.tokens - 1.0)
    }

    // Save updated bucket
    cacheService.set(bucketKey, bucket, expireAfter = config.window * 2)

    val resetAt = now + Duration.seconds(
        ((config.requests.toDouble() - bucket.tokens) / refillRate).toLong()
    )

    return RateLimitResult(
        allowed = allowed,
        remaining = bucket.tokens.toInt(),
        resetAt = resetAt,
        retryAfter = if (allowed) Duration.ZERO else Duration.seconds(1)
    )
}
```

## 4. Usage Examples

### 4.1 Basic Usage

**File: `demo/src/main/kotlin/Server.kt`**

```kotlin
object Server : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())
    val rateLimitSettings = setting("rateLimit", RateLimitSettings())

    // Install interceptor globally
    val rateLimitInterceptor = install(RateLimitInterceptor(rateLimitSettings, cache))

    // Apply rate limiting to specific endpoint
    val limitedEndpoint = path.path("api").path("expensive")
        .post
        .rateLimit(
            requests = 10,
            window = 1.minutes,
            keyStrategy = KeyStrategy.IP
        ) bind HttpHandler {
        HttpResponse.plainText("Success")
    }
}
```

### 4.2 Different Strategies

```kotlin
// Rate limit by authenticated user (100 requests per hour)
val userLimitedEndpoint = path.path("api").path("user-action")
    .post
    .rateLimit(
        requests = 100,
        window = 1.hours,
        keyStrategy = KeyStrategy.USER
    ) bind HttpHandler { /* ... */ }

// Global rate limit (shared across all users)
val globalLimitedEndpoint = path.path("api").path("global-resource")
    .get
    .rateLimit(
        requests = 1000,
        window = 1.minutes,
        keyStrategy = KeyStrategy.GLOBAL
    ) bind HttpHandler { /* ... */ }

// Custom key strategy (e.g., by API key)
val customLimitedEndpoint = path.path("api").path("by-api-key")
    .post
    .rateLimit(
        requests = 50,
        window = 1.minutes,
        keyStrategy = KeyStrategy.CUSTOM,
    ).withCustomKey { request ->
        request.headers["X-API-Key"] ?: "unknown"
    } bind HttpHandler { /* ... */ }
```

### 4.3 Scoped Rate Limits

```kotlin
// Multiple endpoints share the same rate limit scope
val scope = "payment-endpoints"

val createPayment = path.path("payments").post
    .rateLimit(10, 1.minutes, scope = scope)
    bind HttpHandler { /* ... */ }

val refundPayment = path.path("payments").path("refund").post
    .rateLimit(10, 1.minutes, scope = scope)
    bind HttpHandler { /* ... */ }

// Both endpoints share the same 10 req/min limit
```

### 4.4 Default Rate Limiting

**File: `settings.json`**

```json5
{
  "rateLimit": {
    "enabled": true,
    "defaultLimit": {
      "requests": 60,
      "window": "PT1M",  // ISO 8601 duration: 1 minute
      "keyStrategy": "IP"
    },
    "headerPrefix": "X-RateLimit-"
  },
  "cache": {
    "url": "redis://localhost:6379"
  }
}
```

All endpoints without explicit `.rateLimit()` will use the default configuration.

## 5. Integration with Typed Endpoints

**File: `ratelimit/src/main/kotlin/com/lightningkite/lightningserver/ratelimit/TypedEndpointExtensions.kt`**

```kotlin
/**
 * Add rate limiting metadata to typed API endpoints
 */
fun <PATH : PathSpec, USER : HasId<*>?, INPUT, OUTPUT>
ApiHttpHandler<PATH, USER, INPUT, OUTPUT>.withRateLimit(
    requests: Int,
    window: Duration,
    keyStrategy: KeyStrategy = KeyStrategy.IP,
    scope: String? = null
): ApiHttpHandler<PATH, USER, INPUT, OUTPUT> {
    // Attach metadata to the handler's extensions
    return object : ApiHttpHandler<PATH, USER, INPUT, OUTPUT> by this {
        override val extensions: Extensions =
            this@withRateLimit.extensions.plus(
                RateLimitMetadataKey,
                RateLimitMetadata(RateLimitConfig(requests, window, keyStrategy, scope))
            )
    }
}
```

Usage with typed endpoints:

```kotlin
val typedEndpoint = path.path("api").path("action").post.api(
    summary = "Rate limited endpoint",
    authOptions = noAuth,
    implementation = { input: String ->
        "Output"
    }
).withRateLimit(requests = 10, window = 1.minutes)
```

## 6. Testing Strategy

### 6.1 Unit Tests

**File: `ratelimit/src/test/kotlin/RateLimitTest.kt`**

```kotlin
class RateLimitTest {
    @Test
    fun `rate limit allows requests within limit`() = runBlocking {
        val engine = LocalEngine(TestServer.build())

        // Make 10 requests (at limit)
        repeat(10) {
            val response = TestServer.limitedEndpoint.test(engine)
            assertEquals(HttpStatus.OK, response.status)
        }

        // 11th request should be rate limited
        val response = TestServer.limitedEndpoint.test(engine)
        assertEquals(HttpStatus.TooManyRequests, response.status)
    }

    @Test
    fun `rate limit resets after window`() = runBlocking {
        // Use mock clock to test time-based behavior
        // ...
    }
}
```

### 6.2 Integration Tests

Test with different cache backends (Redis, local, DynamoDB) to ensure compatibility.

## 7. Documentation

### 7.1 User Documentation

**File: `docs/rate-limiting.md`**

```markdown
# Rate Limiting

Lightning Server supports per-endpoint rate limiting to protect your API from abuse.

## Setup
1. Add rate limit module dependency
2. Configure cache (required for distributed rate limiting)
3. Install the RateLimitInterceptor
4. Apply rate limits to endpoints

## Configuration
[examples from above]

## Rate Limit Headers
- `X-RateLimit-Limit`: Maximum requests allowed
- `X-RateLimit-Remaining`: Requests remaining in current window
- `X-RateLimit-Reset`: Unix timestamp when limit resets
- `Retry-After`: Seconds to wait before retrying (when rate limited)
```

### 7.2 OpenAPI Integration

Add rate limit information to generated OpenAPI documentation:

```kotlin
// In OpenAPI generator, check for RateLimitMetadata and add to endpoint docs
val rateLimitInfo = endpoint.extensions[RateLimitMetadataKey]
if (rateLimitInfo != null) {
    description += "\n\n**Rate Limit:** ${rateLimitInfo.config.requests} requests per ${rateLimitInfo.config.window}"
}
```

## 8. Advanced Features (Future Enhancements)

### 8.1 Bypass Mechanisms

```kotlin
data class RateLimitConfig(
    // ... existing fields ...
    val bypassHeaderName: String? = null,  // e.g., "X-Admin-Key"
    val bypassHeaderValue: String? = null
)
```

### 8.2 Dynamic Rate Limits

```kotlin
// Rate limits that change based on user tier
fun <PATH : PathSpec> HttpEndpoint<PATH>.rateLimitByUserTier(
    tiers: Map<String, RateLimitConfig>
): HttpEndpoint<PATH>
```

### 8.3 Rate Limit Metrics

Emit telemetry events when rate limits are hit:

```kotlin
runtime.openTelemetry?.let { otel ->
    val meter = otel.getMeter("ratelimit")
    val counter = meter.counterBuilder("ratelimit.exceeded")
        .setDescription("Rate limit exceeded count")
        .build()
    counter.add(1, Attributes.of(
        AttributeKey.stringKey("endpoint"), endpoint,
        AttributeKey.stringKey("strategy"), strategy.name
    ))
}
```

### 8.4 Distributed Rate Limiting

For high-traffic scenarios, implement Redis-based distributed rate limiting with Lua scripts for atomic operations:

```lua
-- atomic_increment.lua
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local current = redis.call('incr', key)
if current == 1 then
    redis.call('expire', key, window)
end
return current
```

## 9. Implementation Checklist

- [ ] Create `ratelimit-shared` module with configuration classes
- [ ] Create `ratelimit` module with interceptor implementation
- [ ] Implement sliding window algorithm with Cache abstraction
- [ ] Add fluent API extensions for endpoint configuration
- [ ] Implement rate limit key generation strategies
- [ ] Add rate limit headers to responses
- [ ] Integrate with typed endpoints
- [ ] Write comprehensive unit tests
- [ ] Write integration tests with different cache backends
- [ ] Create user documentation
- [ ] Add OpenAPI documentation integration
- [ ] Update demo server with examples
- [ ] Test with Redis, local, and DynamoDB caches

## 10. Dependencies

**`ratelimit/build.gradle.kts`:**
```kotlin
dependencies {
    api(project(":core"))
    api(project(":ratelimit-shared"))
    api(libs.services.cache)

    testImplementation(project(":engine-local"))
    testImplementation(libs.kotlin.test)
}
```

**`ratelimit-shared/build.gradle.kts`:**
```kotlin
dependencies {
    api(project(":core-shared"))
    api(libs.kotlinx.json)
}
```

## 11. Key Architectural Decisions

### 11.1 Why Use Cache Abstraction?

The existing `Cache` service abstraction provides:
- Multi-backend support (Redis, Memcached, DynamoDB, local RAM)
- Built-in serialization via KotlinX Serialization
- Atomic operations (increment, add-if-not-exists)
- TTL/expiration support
- Consistent API across deployment targets

This eliminates the need for a separate rate limit storage abstraction.

### 11.2 Why Use Extensions for Metadata?

Lightning Server uses the `Extensions` pattern (similar to Kotlin's context receivers) to attach metadata to endpoints without modifying core types. This allows:
- Non-invasive feature additions
- Type-safe metadata access
- Composability with other features
- No breaking changes to existing code

### 11.3 Why Interceptor-Based?

The `HttpInterceptor` pattern provides:
- Separation of concerns (rate limiting is orthogonal to business logic)
- Execution before handler (early rejection saves resources)
- Access to request and response for header modification
- Consistent with other cross-cutting concerns (CORS, auth)

### 11.4 Algorithm Choice: Sliding Window vs Token Bucket

**Sliding Window (Recommended for initial implementation):**
- Simpler to implement
- Easier to reason about ("X requests per Y time period")
- Works well with cache TTL features
- Sufficient for most use cases

**Token Bucket (Advanced):**
- Smoother rate limiting (allows bursts)
- Better UX for legitimate users
- More complex state management
- Recommended for future enhancement

## 12. Reference: Lightning Server Architecture Patterns

This implementation leverages the following Lightning Server patterns:

1. **ServerBuilder Pattern**: Declarative server definition
2. **Settings Pattern**: Runtime-configurable services via `setting()`
3. **Context Receivers**: `context(ServerRuntime)` for implicit service access
4. **Interceptor Chain**: Middleware via `HttpInterceptor`
5. **Extensions Pattern**: Metadata attachment via `Extensions.Key`
6. **Service Abstraction**: Backend-agnostic via `Cache.Settings()`
7. **Request Cache**: Sharing data between interceptors via `request.cache`
8. **Typed Paths**: Type-safe URL argument access via `PathSpec`

## 13. Summary

This plan provides a complete, production-ready rate limiting solution that:

1. ✅ Follows Lightning Server architectural patterns
2. ✅ Uses existing service abstractions (Cache)
3. ✅ Provides a type-safe, declarative API
4. ✅ Works with all deployment targets (local, serverless, Ktor)
5. ✅ Integrates with authentication system
6. ✅ Supports multiple rate limiting strategies
7. ✅ Returns standard rate limit headers
8. ✅ Is fully testable with mock implementations
9. ✅ Integrates with OpenAPI documentation
10. ✅ Extensible for future enhancements

The implementation prioritizes accuracy, maintainability, and consistency with the existing codebase patterns.
