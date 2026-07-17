> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Rate Limiting

Rate limiting protects your server from callers that consume a disproportionate share of its
time — either through bursts of fast requests, slow expensive calls, or abuse.  Lightning
Server's rate limiter is a single interceptor (`RateLimitInterceptor`) backed by any
configured `Cache`.  It covers both HTTP and WebSocket connections.

The limiter uses a **borrow-and-repay** model: each request reserves a nominal slice of future
server time up front, then refunds the unused portion after the request completes.  When a
caller's running reservation would extend further than the configured `leeway` into the future,
subsequent requests receive HTTP 429 until the reservation catches up with real time.

---

## Imports

All examples in this chapter use the following imports:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.ratelimit.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.services.cache.*
import kotlin.time.Duration.Companion.seconds
```

---

## Adding the dependency

The rate limiter lives in the `:ratelimit` module.  Add it to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.lightningkite.lightningserver:ratelimit:$lightningServerVersion")
}
```

It depends on `:core` and `com.lightningkite.services:services-cache` (already on your
classpath if you use `Cache` elsewhere).

---

## Enabling rate limiting

Rate limiting is an interceptor, so you install it with `install(...)` inside your
`ServerBuilder`.  You supply three things:

1. A `Runtime<RateLimitSettings?>` — resolved from a `setting` declaration.  When the
   runtime value is `null`, rate limiting is completely disabled (useful for staging).
2. A `Runtime<Cache>` — where per-key reservation state is stored.
3. A `requestLimits` lambda — maps each incoming request to a `RequestLimits`, or returns
   `null` to exempt the request entirely.

```kotlin
object Server : ServerBuilder() {
    val rateLimitConfig = setting("rateLimit", RateLimitSettings())
    val cache           = setting("cache", Cache.Settings())

    val rateLimiter = install(
        RateLimitInterceptor(
            settings      = rateLimitConfig,
            cache         = cache,
            requestLimits = { request ->
                // Key every request by the caller's IP address.
                // Return null to exempt a request (no limit applied).
                RequestLimits(key = request.sourceIp)
            }
        )
    )

    val api = path.path("api").get bind HttpHandler {
        HttpResponse.plainText("OK")
    }
}
```

`install(...)` registers the interceptor globally — every HTTP handler and every WebSocket
connection in this `ServerBuilder` is covered from that point on.  Install `RateLimitInterceptor`
after `CorsInterceptor` so that CORS preflight `OPTIONS` requests are not counted against the
caller's budget.

---

## RateLimitSettings

`RateLimitSettings` is a `@Serializable` data class, so its fields are controlled via
`settings.json` without a code change:

| Field | Type | Default | Effect |
|---|---|---|---|
| `headerPrefix` | `String` | `"X-RateLimit-"` | Prefix for diagnostic response headers |
| `rateLimiterId` | `String` | `"general"` | Namespace for cache keys; use different values for independent limiters |
| `includeHeaders` | `Boolean` | `true` | When `true`, adds diagnostic headers to every response |

A typical `settings.json` entry looks like:

```json
{
  "rateLimit": {
    "headerPrefix": "X-RateLimit-",
    "rateLimiterId": "api",
    "includeHeaders": true
  }
}
```

Set `"rateLimit": null` in `settings.json` (or use `Runtime.Constant(null)` in code) to
disable rate limiting without touching the `ServerBuilder`.

---

## RequestLimits

The `requestLimits` lambda returns a `RequestLimits` data class for each request.  Returning
`null` exempts the request from all limiting.

```kotlin
data class RequestLimits(
    val key        : String,
    val multiplier : Double   = 10.0,
    val leeway     : Duration = 200.seconds,
    val borrowTime : Duration = 10.seconds,
    val overhead   : Duration = 0.25.seconds,
)
```

| Field | Effect |
|---|---|
| `key` | Groups requests for budget sharing.  Requests with the same key share one reservation. |
| `multiplier` | Scale factor applied to the measured request duration.  Higher → stricter (each second of real work consumes more budget). |
| `leeway` | How far ahead of real time a caller may reserve before being rejected.  Larger values permit bigger bursts. |
| `borrowTime` | The nominal reservation made at the start of each request.  Affects concurrency headroom, not throughput limit. |
| `overhead` | Extra duration charged after each request to account for work not captured by measured time (load balancer overhead, serialization, etc.). |

**Keying strategies:**

```kotlin
// IP-based — anonymous callers, public APIs
requestLimits = { request ->
    RequestLimits(key = request.sourceIp)
}

// User-based — authenticated APIs (tighter or looser than IP)
requestLimits = { request ->
    val userId = /* resolve from auth token in request.headers */ null
    if (userId != null)
        RequestLimits(key = "user:$userId", multiplier = 5.0)
    else
        RequestLimits(key = "anon:${request.sourceIp}")
}

// Exempt internal health-check paths entirely
requestLimits = { request ->
    if (request.path.asString.startsWith("/meta/")) null
    else RequestLimits(key = request.sourceIp)
}
```

---

## Per-caller limits: tuning the numbers

The limiter uses time as a budget unit.  A rough mental model:

```
budget available = leeway
budget consumed  = (measured request time + overhead) × multiplier
```

When consumed budget would push the caller's reservation past `leeway` into the future,
the next request is rejected.

**Worked example** — defaults:

- `leeway = 200s`, `multiplier = 10.0`, `overhead = 0.25s`
- A 1-second request costs `(1 + 0.25) × 10 = 12.5s` of reservation.
- After 200 / 12.5 ≈ 16 such requests with no pause, the next is rejected.
- If the caller pauses 1 second between requests, the reservation shrinks by 1s each pause,
  so they can sustain roughly one request every 12.5 seconds indefinitely.

Tighten the limit by raising `multiplier` or lowering `leeway`.  Loosen it by doing the
opposite.  Use `overhead` to charge for per-request fixed costs that the measured duration
misses.

---

## Response when limited

A caller who exceeds their budget receives:

- **HTTP 429 Too Many Requests**
- Body: `LSError` JSON with `detail = "rate-limit-{rateLimiterId}"` and a human-readable
  `message` that includes `at` (when they may retry) and `wait` (duration to wait)

```json
{
  "http": 429,
  "detail": "rate-limit-api",
  "message": "You're asking too much from the server; please wait before trying again.",
  "data": "{\"at\":\"2026-01-01T12:00:30Z\",\"wait\":\"PT3S\"}"
}
```

When `includeHeaders = true`, successful responses also carry diagnostic headers:

| Header | Value |
|---|---|
| `X-RateLimit-Identity` | The `key` string used to identify this caller |
| `X-RateLimit-RemainingTime` | How much reservation time the caller has left relative to now (negative = time until they can make the next free request) |
| `X-RateLimit-AvailableAfter` | Absolute timestamp when the next request will be free to proceed |

---

## WebSocket connections

`RateLimitInterceptor` also implements `WebSocketHandlerInterceptor`.  A WebSocket connection
request goes through the same budget check during `willConnect`.  If the caller is over budget,
the upgrade is rejected before the WebSocket opens.  Because `install(...)` handles both
`HttpInterceptor` and `WebSocketHandlerInterceptor` in a single call, no extra wiring is
needed.

---

## Testing

In unit tests, set the cache to the RAM implementation and use a fixed `RateLimitSettings`:

```kotlin
class RateLimitTest {
    object TestServer : ServerBuilder() {
        val rateLimitConfig = setting("rateLimit", RateLimitSettings(rateLimiterId = "test"))
        val cache           = setting("cache", Cache.Settings("ram"))

        val limiter = install(
            RateLimitInterceptor(
                settings      = rateLimitConfig,
                cache         = cache,
                requestLimits = { request -> RequestLimits(key = request.sourceIp) }
            )
        )

        val ping = path.path("ping").get bind HttpHandler {
            HttpResponse.plainText("pong")
        }
    }

    @Test
    fun eventuallyThrottles() {
        TestServer.testBlocking(settings = {}) {
            // Drive the endpoint until the limiter fires.
            // The exact count depends on leeway/multiplier/actual test latency.
            var blocked = false
            repeat(200) {
                try {
                    TestServer.ping.test()
                } catch (e: HttpStatusException) {
                    if (e.status.code == 429) { blocked = true; return@repeat }
                    throw e
                }
            }
            check(blocked) { "Expected a 429 from the rate limiter" }
        }
    }
}
```

> **Note on test timings**: The rate limiter measures wall-clock time.  In unit tests the
> request duration is near zero, so the budget depletes slowly.  Test with enough iterations
> or temporarily set a very tight `multiplier` / `leeway` combination.

---

## What's Next

- **CORS & Interceptors** — install `CorsInterceptor` before `RateLimitInterceptor` so that
  preflight requests are not counted against the caller's budget.
- **Authentication** — key the limiter by user ID instead of IP for authenticated endpoints;
  this prevents IP sharing (NAT, corporate proxies) from causing false positives.
- **Bulk requests** — the `/meta/bulk` endpoint fans out to multiple sub-handlers.  Each
  sub-request is metered by the inner handler, not by the bulk wrapper, so the budget
  cost is proportional to actual work done.
