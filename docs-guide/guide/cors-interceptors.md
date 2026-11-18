# CORS and interceptors

Interceptors wrap request handling for concerns that apply to every endpoint: CORS, security headers, logging,
maintenance mode, [rate limiting](rate-limiting.md).  The most common one is `CorsInterceptor`, which lets browsers on
other origins call your API.

<!-- sample: com/lightningkite/lightningserver/guide/samples/CorsInterceptorsSamples.kt#cors-server -->
```kotlin
object CorsServer : ServerBuilder() {
    val cors = setting("cors", CorsSettings.forProduction("https://app.example.com"))

    init {
        registerBasicMediaTypeCoders()
        install(CorsInterceptor(cors))
    }

    val hello = path.path("api").path("hello").get bind HttpHandler {
        HttpResponse.plainText("Hello!")
    }
}
```

`install` applies an interceptor to the whole server, including modules mounted with `include`; one installed inside
a module applies to the whole server too.  `CorsSettings` is a
setting, so production origins live in the settings file rather than in code.  `registerBasicMediaTypeCoders()` lets
the server encode error bodies, such as the 403 for a disallowed origin; without it, errors become a bare 500.

<!-- sample: com/lightningkite/lightningserver/guide/samples/CorsInterceptorsSamples.kt#cors-test -->
```kotlin
fun corsAllowedOriginTest() = CorsServer.testBlocking(settings = {}) {
    val allowed = CorsServer.hello.test(headers = HttpHeaders { add(HttpHeader.Origin, "https://app.example.com") })
    assertEquals("https://app.example.com", allowed.headers[HttpHeader.AccessControlAllowOrigin]?.root)

    val rejected = CorsServer.hello.test(headers = HttpHeaders { add(HttpHeader.Origin, "https://evil.example") })
    assertEquals(HttpStatus.Forbidden, rejected.status)
}
```

Imports used in this chapter:

<!-- sample: com/lightningkite/lightningserver/guide/samples/CorsInterceptorsSamples.kt#cors-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.cors.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
```

## CORS settings

| Field | Default | Effect |
|---|---|---|
| `limitToDomains` | `[]` | Allowed origins.  Empty allows none; `["*"]` allows all. |
| `limitToHeaders` | `[]` | Request headers allowed in a preflight.  `["*"]` echoes whatever the browser asks for. |
| `limitToMethods` | `[]` | Methods allowed in a preflight, out of those registered at the path.  `["*"]` allows them all. |
| `exposedHeaders` | `[]` | Response headers browser code may read beyond the CORS-safe ones. |
| `allowCredentials` | `false` | Sends `Access-Control-Allow-Credentials: true`, so the browser includes cookies and `Authorization`. |
| `cacheLength` | `null` | How long the browser may cache a preflight (`Access-Control-Max-Age`).  `null` sends no header. |
| `forbidOnMatchFail` | `true` | Rejects a disallowed origin with 403.  When `false`, the request runs but gets no CORS headers, so the browser hides the response. |

Two presets:

- `CorsSettings.forProduction(vararg origins)`: only those origins, any header and method, credentials on, preflight
  cached 10 seconds, disallowed origins rejected.
- `CorsSettings.allowAll()`: every origin, header and method, credentials on, nothing rejected.  Local development
  only.

In the settings file, `cacheLength` is an ISO-8601 duration:

```json
{
  "cors": {
    "limitToDomains": ["https://app.example.com", "https://*.example.com"],
    "limitToHeaders": ["*"],
    "limitToMethods": ["*"],
    "allowCredentials": true,
    "cacheLength": "PT1H"
  }
}
```

### Origin patterns

| Pattern | Matches |
|---|---|
| `https://app.example.com` | Exactly that origin. |
| `app.example.com` | That host over any scheme. |
| `https://*.example.com` | Any subdomain over HTTPS, not `example.com` itself. |
| `*.example.com` | Any subdomain over any scheme. |
| `*` | Every origin. |

Matching ignores case.  A matching request gets its own origin back in `Access-Control-Allow-Origin`, plus
`Vary: Origin`.  A subdomain wildcard trusts every subdomain: use it only if you control all of them.

### What the interceptor does

- **No `Origin` header** (same-origin requests, mobile apps, `curl`): passes through untouched.  CORS only tells
  browsers what they may read; it is not access control.
- **Preflight (`OPTIONS` with an allowed origin):** answered by the interceptor with 204, listing the methods
  registered at that path (`HEAD` included when there's a `GET`) filtered by `limitToMethods`.  You don't declare
  `OPTIONS` routes, and handlers and interceptors installed after CORS never see the preflight.  A path with no
  handler gets 404.
- **Other requests:** run normally, then get the CORS headers (`Access-Control-Expose-Headers` included).
- **WebSockets:** the origin is checked when the socket connects, and a disallowed origin is always refused with 403,
  whatever `forbidOnMatchFail` says, because browsers don't apply CORS to sockets.  Sockets multiplexed inside one
  connection are not checked separately.

If `limitToHeaders` isn't `["*"]`, include every header your clients send: `Content-Type`, `Authorization`, and
`traceparent` if they send trace context (see [Observability](observability.md)).

A 403 whose origin is the server's own public URL means that URL is missing from `limitToDomains`.

## Writing an HTTP interceptor

An `HttpInterceptor` receives the request and `cont`, the rest of the chain.  Call `cont` and change the response:

<!-- sample: com/lightningkite/lightningserver/guide/samples/CorsInterceptorsSamples.kt#interceptor-served-by -->
```kotlin
class ServedBy(private val instance: String) : HttpInterceptor {
    context(runtime: ServerRuntime)
    override suspend fun <PATH : PathSpec> intercept(
        request: HttpRequest<PATH>,
        cont: suspend context(ServerRuntime) (HttpRequest<PATH>) -> HttpResponse,
    ): HttpResponse {
        val response = cont(request)
        return response.copy(headers = response.headers.copy { add("X-Served-By", instance) })
    }
}
```

Or answer without calling `cont`, and the handler never runs:

<!-- sample: com/lightningkite/lightningserver/guide/samples/CorsInterceptorsSamples.kt#interceptor-maintenance -->
```kotlin
class MaintenanceMode(private val enabled: Runtime<Boolean>) : HttpInterceptor {
    context(runtime: ServerRuntime)
    override suspend fun <PATH : PathSpec> intercept(
        request: HttpRequest<PATH>,
        cont: suspend context(ServerRuntime) (HttpRequest<PATH>) -> HttpResponse,
    ): HttpResponse {
        if (enabled()) return HttpResponse.plainText("Down for maintenance", status = HttpStatus.ServiceUnavailable)
        return cont(request)
    }
}
```

Throwing works too: an exception from an interceptor (or anything inside it) goes through the server's exception
handler, so a thrown `HttpStatusException` (such as `ForbiddenException()`) keeps its status, and the interceptors
outside still post-process the error response.

`name` (default: the class name) labels the interceptor's span in traces.

HTTP interceptors run once per request the engine received.  They don't see each part of a `/meta/bulk` call or an
endpoint called from server code; use an execution interceptor for those.

## Order

<!-- sample: com/lightningkite/lightningserver/guide/samples/CorsInterceptorsSamples.kt#interceptor-server -->
```kotlin
object InterceptedServer : ServerBuilder() {
    val cors = setting("cors", CorsSettings.forProduction("https://app.example.com"))
    val maintenance = setting("maintenance", false)

    init {
        registerBasicMediaTypeCoders()
        install(SecurityHeadersInterceptor())
        install(CorsInterceptor(cors))
        install(MaintenanceMode(maintenance))
        install(ServedBy("api-1"))
        install(SlowExecutionLog)
    }

    val hello = path.path("api").path("hello").get bind HttpHandler {
        HttpResponse.plainText("Hello!")
    }
}
```

The first interceptor installed is the outermost: it sees the request first and the response last.  Install
`SecurityHeadersInterceptor` first so its headers land on every response, then CORS, then anything that can reject a
request (auth checks, maintenance mode, rate limits).  That way rejections still carry CORS headers and the browser can
read them, and preflights are answered before anything can reject them:

<!-- sample: com/lightningkite/lightningserver/guide/samples/CorsInterceptorsSamples.kt#interceptor-test -->
```kotlin
fun interceptorOrderTest() = InterceptedServer.testBlocking(settings = { maintenance set true }) {
    val response = InterceptedServer.hello.test(headers = HttpHeaders { add(HttpHeader.Origin, "https://app.example.com") })
    assertEquals(HttpStatus.ServiceUnavailable, response.status)
    // MaintenanceMode answered, but CORS is outside it, so the browser can still read the 503.
    assertEquals("https://app.example.com", response.headers[HttpHeader.AccessControlAllowOrigin]?.root)
    // ServedBy is inside MaintenanceMode, so it never ran.
    assertNull(response.headers["X-Served-By"])
}
```

## Execution interceptors

An `ExecutionInterceptor` wraps every execution the server runs: HTTP requests (including `/meta/bulk` parts and
endpoints called from server code), each WebSocket phase, tasks, schedule ticks, startup and pre-deploy tasks.

<!-- sample: com/lightningkite/lightningserver/guide/samples/CorsInterceptorsSamples.kt#interceptor-execution -->
```kotlin
object SlowExecutionLog : ExecutionInterceptor {
    context(runtime: ServerRuntime)
    override suspend fun <T> intercept(
        invocation: Invocation,
        cont: suspend context(ServerRuntime) () -> T,
    ): T {
        val started = TimeSource.Monotonic.markNow()
        try {
            return cont()
        } finally {
            val elapsed = started.elapsedNow()
            if (elapsed > 1.seconds) println("Slow execution ${invocation.execution}: $elapsed")
        }
    }
}
```

`invocation` says what is running: `Invocation.Http` (with `request` and `origin`: `Engine`, `Client` for a bulk
part, `Server` for a call from code), `Invocation.WebSocket`, or `Invocation.Unrequested` for tasks and the like.
`invocation.virtual` (on HTTP and WebSocket invocations) is `true` for anything the engine didn't receive directly.
If `cont` isn't called, the execution doesn't run.

Execution interceptors run outside the HTTP chain.  If one throws during an HTTP execution, the exception handler
turns it into the response, keeping an `HttpStatusException`'s status.  Throw before calling `cont`: the error
response then still passes through the HTTP chain and gets CORS and security headers.  After `cont`, the HTTP chain
has already run, so they're missing.  During any other kind of execution, a throw fails that execution.

## WebSocket interceptors

A `WebSocketInterceptor` wraps the handler of every socket a client opens, across all its phases:
`intercept(handler)` returns a handler, usually one that delegates to `handler` and adds behavior around
`willConnect`.  It doesn't see sockets multiplexed inside a connection.  `CorsInterceptor` is one.

## Installing

`install` has an overload for each combination of the three kinds, so one object that is, say, both an
`HttpInterceptor` and a `WebSocketInterceptor` (like `CorsInterceptor`), or both an `ExecutionInterceptor` and an
`HttpInterceptor` (like `RateLimitInterceptor`), is registered in every chain it belongs to with one call.  `install`
returns its argument.

## Built-in interceptors

| Interceptor | Kind | What it does |
|---|---|---|
| `CorsInterceptor(settings)` | HTTP, WebSocket | CORS, as above. |
| `SecurityHeadersInterceptor(hstsMaxAgeSeconds)` | HTTP | `X-Content-Type-Options: nosniff` everywhere, `Strict-Transport-Security` over HTTPS (default max-age one year).  Headers a handler set are kept. |
| `GzipInterceptor()` (`compression` package) | HTTP | Gzips responses for clients that accept it. |
| `AccessLogInterceptor()` (`auth`) | Execution | One INFO line per HTTP request and per socket open/close, naming the principal. |
| `RateLimitInterceptor(...)` (`ratelimit`) | Execution, HTTP | Per-client rate limits; see [Rate limiting](rate-limiting.md). |

## Next

- [Rate limiting](rate-limiting.md): the interceptor most servers install after CORS.
- [Error handling](error-handling.md): how thrown exceptions become responses.
- [Observability](observability.md): interceptor spans and trace propagation.
- [Testing](testing.md): `handler.test(headers = ...)` runs the HTTP interceptors.
