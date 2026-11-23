# Rate limiting

`RateLimitInterceptor` (module `ratelimit`) throttles each caller to a budget of server time, kept in any `Cache`.
Install it when one client could otherwise starve the rest: public APIs, login endpoints, expensive queries.

```kotlin
dependencies {
    implementation("com.lightningkite.lightningserver:ratelimit:$lightningServerVersion")
}
```

## Minimal example

<!-- sample: com/lightningkite/lightningserver/guide/samples/RateLimitingSamples.kt#ratelimit-server -->
```kotlin
object LimitedServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val cors = setting("cors", CorsSettings())
    // Nullable, so `"rateLimit": null` in settings.json turns limiting off.
    val rateLimit = setting<RateLimitSettings?>("rateLimit", RateLimitSettings())

    val meta = path.path("meta") module MetaEndpoints("com.example", cache)
    val multiplex = path.path("multiplex") bind MultiplexWebSocketHandler()

    val corsInterceptor = install(CorsInterceptor(cors))
    val rateLimiter = install(
        RateLimitInterceptor(
            settings = rateLimit,
            cache = cache,
            exempt = listOf(meta.bulk),
            exemptSockets = listOf(multiplex),
        ) { request ->
            RequestLimits(key = "ip:${request.sourceIp}")
        }
    )

    val hello = path.path("hello").get bind HttpHandler { HttpResponse.plainText("Hello!") }
}
```

The interceptor applies to the whole server.  The lambda maps each request to a `RequestLimits`; requests with the
same `key` share one budget.  Return `null` to let a request through unchecked.

What is checked and charged, each on its own:

- every request the engine received,
- every part of a `/meta/bulk` call,
- every WebSocket connect, including each virtual socket opened over a `MultiplexWebSocketHandler`.

Endpoints called from server code are not charged separately; the request that made the call already pays for its
time.  Messages and disconnects on an open socket are never charged.

Pass handlers that only carry other requests in `exempt` (HTTP) and `exemptSockets` (WebSocket): `/meta/bulk` and the
multiplex socket.  What they carry is charged already, so charging the wrapper too would count the work twice.

Install it after `CorsInterceptor`, so a browser can read the 429s and rate-limit headers.

## Testing it

<!-- sample: com/lightningkite/lightningserver/guide/samples/RateLimitingSamples.kt#ratelimit-test -->
```kotlin
fun rateLimitTest() {
    val frozen = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(1_000_000)
    }
    LimitedServer.testBlocking(settings = {}, clock = { frozen }) {
        val first = LimitedServer.hello.test()
        assertEquals(HttpStatus.OK, first.status)
        assertEquals("ip:local", first.headers["X-RateLimit-Identity"]?.root)

        // With the clock frozen, every request spends budget and none is earned back.
        val responses = (1..200).map { LimitedServer.hello.test() }
        val rejected = responses.first { it.status == HttpStatus.TooManyRequests }
        assertNotNull(rejected.headers[HttpHeader.RetryAfter])
    }
}
```

The limiter reads time from the runtime's clock, so passing a fixed `clock` to `testBlocking` makes the outcome
deterministic.  A rejection comes back as a 429 response, not a thrown exception.

## Keying by user and pricing endpoints

<!-- sample: com/lightningkite/lightningserver/guide/samples/RateLimitingSamples.kt#ratelimit-keys -->
```kotlin
object PerUserLimitedServer : ServerBuilder() {
    val cache = setting("cache", Cache.Settings())
    val rateLimit = setting<RateLimitSettings?>("rateLimit", RateLimitSettings())

    val search = path.path("search").get bind HttpHandler { HttpResponse.plainText("results") }

    val rateLimiter = install(RateLimitInterceptor(rateLimit, cache) { request ->
        val auth = request[Authentication.CacheKey]
        val handler = (request as? HttpRequest<*>)?.path?.tryResolve()?.value
        RequestLimits(
            key = auth?.let { "user:${it.principalName}:${it.rawId}" } ?: "ip:${request.sourceIp}",
            multiplier = if (handler === search) 30.0 else 10.0,
        )
    })
}
```

Signed-in callers get their own budget, so users behind one NAT or corporate proxy don't throttle each other.
`request.sourceIp` is the address the engine reports.  Behind a proxy or load balancer, check that it is the client's
and not the balancer's, or every anonymous caller shares one budget.

To meter only some endpoints, return `null` for the others.  Comparing the resolved handler with `===` works for any
handler you hold a reference to.

## Turning it off

<!-- sample: com/lightningkite/lightningserver/guide/samples/RateLimitingSamples.kt#ratelimit-disable -->
```kotlin
fun rateLimitOffTest() = LimitedServer.testBlocking(settings = { rateLimit set null }) {
    val response = LimitedServer.hello.test()
    assertNull(response.headers["X-RateLimit-Identity"])
}
```

In `settings.json`:

```json
{ "rateLimit": null }
```

## How the budget works

The cache stores, per key, the instant the caller has reserved server time up to.

1. A request is rejected if that reservation is already in the future.
2. Otherwise it reserves `borrowTime` up front.  An idle caller's reservation never lags more than `leeway` behind
   now, so `leeway` is how much burst a caller has saved up.
3. When the request finishes, it is charged `(duration + overhead) × multiplier` and refunded the rest of what it
   borrowed.  A cancelled request (for example, the client disconnected) is still charged.
4. A rejected request costs `borrowTime`.  A blocked caller that retries more often than that stays blocked; one that
   waits as `Retry-After` says gets in.

With the defaults a request taking 50 ms costs `(0.05 + 0.25) × 10 = 3 s` of budget.  An idle caller can burst roughly
`leeway / cost` requests (here about 65), then sustains one request per `cost` of wall-clock time.

Tighten the limit by raising `multiplier` or lowering `leeway`.  Use `overhead` to charge a fixed amount per request
whatever its duration.

### `RequestLimits`

| Field | Default | Effect |
|---|---|---|
| `key` | (required) | Requests with the same key share one budget. |
| `multiplier` | `10.0` | Scales the time charged.  Must be greater than 0. |
| `leeway` | `200.seconds` | How far behind now an idle caller's reservation may lag: the burst size. |
| `borrowTime` | `10.seconds` | Reserved up front by each request; the cost of a rejected request. |
| `overhead` | `0.25.seconds` | Added to each request's measured duration before multiplying. |

### `RateLimitSettings`

| Field | Default | Effect |
|---|---|---|
| `headerPrefix` | `"X-RateLimit-"` | Prefix of the diagnostic headers. |
| `rateLimiterId` | `"general"` | Namespaces the cache keys; give each installed limiter its own. |
| `includeHeaders` | `true` | Adds the diagnostic headers to responses. |

## Responses

An over-budget HTTP request gets a 429 with a `Retry-After` header (whole seconds) and an `LSError` body:

```json
{
  "http": 429,
  "detail": "rate-limit-general",
  "message": "You're asking too much from the server; please wait before trying again.",
  "data": "{\"at\":\"2026-01-01T12:00:30Z\",\"wait\":\"PT3S\"}"
}
```

A rejected socket connect fails before it opens; a rejected virtual socket on the multiplexer gets an `end` message
carrying the error.

With `includeHeaders`, every response, rejected ones included, carries:

| Header | Value |
|---|---|
| `X-RateLimit-Identity` | The `key` the request was charged to. |
| `X-RateLimit-RemainingTime` | Budget left as a duration; negative means the caller must wait that long. |
| `X-RateLimit-AvailableAfter` | The instant the caller has reserved up to. |

A `/meta/bulk` response reports the furthest any of its parts reserved or was told to wait.

!!! note
    Browsers only let scripts read these headers if CORS exposes them.  Add `Retry-After` and the `X-RateLimit-*`
    names to `CorsSettings.exposedHeaders` if your web client needs them.

## Next

- [CORS & interceptors](cors-interceptors.md): interceptor order
- [Bulk endpoints](bulk-endpoints.md): how `/meta/bulk` parts are routed
- [Caching](caching.md): choosing the cache that holds the budgets
- [Authentication](auth.md): where `Authentication` comes from
