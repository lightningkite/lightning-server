> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# CORS & Interceptors

CORS (Cross-Origin Resource Sharing) lets browsers call your API from a different origin.
Without it, every request from a web front-end served on a different domain is blocked by
the browser before it arrives.

Lightning Server handles CORS through `CorsInterceptor` — a built-in interceptor that
validates origins, responds to preflight `OPTIONS` requests automatically, and injects the
required response headers.  The same interceptor mechanism is available for your own
cross-cutting concerns: logging, request tracing, injecting common headers, rate limiting.

## Imports

All examples in this chapter use the following imports:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.cors.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import kotlin.time.Duration.Companion.seconds
```

## Configuring CORS

`CorsSettings` is a plain `@Serializable` data class, so declare it as a `setting` and it
becomes a field in `settings.json`:

```kotlin
object Server : ServerBuilder() {
    val cors = setting("cors", CorsSettings.forProduction("https://app.example.com"))

    val corsInterceptor = install(CorsInterceptor(cors))

    val hello = path.path("api").path("hello").get bind HttpHandler {
        HttpResponse.plainText("Hello!")
    }
}
```

`install(CorsInterceptor(cors))` registers the interceptor globally — every HTTP handler
and every WebSocket connection in this `ServerBuilder` is covered.  The call returns the
`CorsInterceptor` instance; you rarely need to hold the reference.

## CorsSettings Fields

| Field | Type | Default | Effect |
|---|---|---|---|
| `limitToDomains` | `List<String>` | `emptyList()` | Allowed origins. Empty = none allowed; `["*"]` = all allowed. |
| `limitToHeaders` | `List<String>` | `emptyList()` | Allowed request headers. `["*"]` = mirror the request's `Access-Control-Request-Headers`. |
| `limitToMethods` | `List<String>` | `emptyList()` | Allowed HTTP methods. `["*"]` = mirror the endpoint's actual methods. |
| `exposedHeaders` | `List<String>` | `emptyList()` | Response headers exposed to browser JS beyond the CORS-safe defaults. |
| `allowCredentials` | `Boolean` | `false` | Adds `Access-Control-Allow-Credentials: true`. Requires exact origins, not `"*"`. |
| `cacheLength` | `Duration?` | `null` | `Access-Control-Max-Age` for preflight caching. `null` = no header sent. |
| `forbidOnMatchFail` | `Boolean` | `true` | When `true`, non-matching origins receive 403. When `false`, the request proceeds but without CORS headers, so the browser blocks the response. |

### Domain patterns

`limitToDomains` supports three pattern forms:

- `"https://app.example.com"` — exact match including scheme
- `"*.example.com"` — any subdomain, any scheme
- `"https://*.example.com"` — any subdomain, HTTPS only
- `"*"` — any origin (development only; never use in production)

When an origin matches, the actual origin is reflected in `Access-Control-Allow-Origin`
(not the pattern), and `Vary: Origin` is added to prevent cache poisoning.

## Presets

`CorsSettings.forProduction(vararg origins: String)` restricts origins to those you name,
mirrors all headers and methods, enables credentials, and caches preflight responses for
10 seconds:

```kotlin
val cors = setting("cors", CorsSettings.forProduction(
    "https://app.example.com",
    "https://admin.example.com"
))
```

`CorsSettings.allowAll()` mirrors everything and never rejects an origin.  Use it only
during local development:

```kotlin
// Development only — never deploy with allowAll()
val cors = setting("cors", CorsSettings.allowAll())
```

> `allowAll()` sets `allowCredentials = true` with a wildcard origin, which technically
> violates the CORS spec.  Browsers differ in how they handle it; use only locally.

## How Preflight Requests Work

When a browser sends a "complex" cross-origin request (non-simple method or custom headers),
it first issues a preflight `OPTIONS` request.  `CorsInterceptor` handles this automatically:

1. Looks up which HTTP methods are actually registered for the requested path.
2. Filters by `limitToMethods` if a non-wildcard list is configured.
3. Returns `204 No Content` with the appropriate `Access-Control-Allow-Methods` and
   `Access-Control-Allow-Headers` headers.
4. Returns `404` if no handler exists at that path.

You do not need to define `OPTIONS` routes yourself.

```
Browser → OPTIONS /api/users
          Origin: https://app.example.com
          Access-Control-Request-Method: POST
          Access-Control-Request-Headers: Content-Type

Server  → 204 No Content
          Access-Control-Allow-Origin: https://app.example.com
          Access-Control-Allow-Methods: GET,POST
          Access-Control-Allow-Headers: Content-Type
          Access-Control-Max-Age: 10
          Access-Control-Allow-Credentials: true
          Vary: Origin
```

## WebSocket CORS

`CorsInterceptor` implements `WebSocketHandlerInterceptor` as well, so WebSocket connections
go through the same origin check at handshake time.  A mismatched origin always produces
`403 Forbidden` for WebSocket connections regardless of `forbidOnMatchFail` — WebSocket
upgrades must be validated at connection time because the browser itself does not enforce
CORS on them.

## Writing a Custom Interceptor

`HttpInterceptor` is a `fun interface`.  The lambda receives the `request` and a `cont`
continuation — call `cont(request)` to proceed to the next interceptor (or the handler),
modify the result, or return early:

```kotlin
val requestLogger = HttpInterceptor { request, cont ->
    println("→ ${request.path.method} /${request.path.pathSegments.joinToString("/")}")
    val response = cont(request)
    println("← ${response.status}")
    response
}

object LoggedServer : ServerBuilder() {
    init {
        install(requestLogger)
    }

    val hello = path.path("hello").get bind HttpHandler {
        HttpResponse.plainText("Hi!")
    }
}
```

For a stateful interceptor, or one that needs a meaningful `name` for instrumentation,
implement the interface as a class:

```kotlin
class HeaderInjector(
    private val headerName: String,
    private val headerValue: String,
) : HttpInterceptor {
    override val name: String = "HeaderInjector($headerName)"

    context(runtime: ServerRuntime)
    override suspend fun intercept(
        request: HttpRequest<*>,
        cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse,
    ): HttpResponse {
        val response = cont(request)
        return response.copy(
            headers = response.headers.copy { add(headerName, headerValue) }
        )
    }
}
```

Install it the same way:

```kotlin
init {
    install(HeaderInjector("X-Served-By", "lightning"))
}
```

Interceptors can also short-circuit and return a response without calling `cont`:

```kotlin
val maintenanceMode = HttpInterceptor { _, _ ->
    HttpResponse(HttpStatus.ServiceUnavailable, body = HttpBody.text("Down for maintenance"))
}
```

> The `name` property on `HttpInterceptor` defaults to `this::class.simpleName ?: "anonymous"`.
> Lambda interceptors get `"anonymous"`.  Name your interceptors via a class if you want
> them to appear meaningfully in telemetry traces.

## Interceptor Ordering

Interceptors execute in installation order.  The first installed is the outermost wrapper
(runs first before passing to `cont`); later interceptors run closer to the handler.  Given:

```kotlin
init {
    install(outer)
    install(inner)
}
```

The call chain is: `outer.intercept` → `inner.intercept` → handler → `inner` returns →
`outer` returns.

**Install `CorsInterceptor` first** so that preflight `OPTIONS` requests get CORS headers
even when later interceptors would otherwise reject the request (for example, because it
lacks an auth token).

## settings.json

`CorsSettings` serializes as a JSON object.  A typical production entry looks like:

```json
{
  "cors": {
    "limitToDomains": ["https://app.example.com"],
    "limitToHeaders": ["*"],
    "limitToMethods": ["*"],
    "allowCredentials": true,
    "cacheLength": "PT10S",
    "forbidOnMatchFail": true
  }
}
```

`cacheLength` is a Kotlin `Duration` and serializes in ISO 8601 format: `"PT10S"` = 10
seconds, `"PT1H"` = 1 hour, `"PT24H"` = 24 hours.  `null` omits the header entirely.

## What's Next

- **Authentication** — add auth interceptors after CORS so preflight `OPTIONS` requests
  bypass auth checks.
- **WebSockets** — CORS protection applies automatically once `CorsInterceptor` is
  installed; no per-handler configuration needed.
- **Caching** — the next chapter covers the `Cache` API: get/set, TTL, distributed locks,
  and atomic counters.
