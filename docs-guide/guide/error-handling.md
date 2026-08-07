# Error Handling & Exceptions

Lightning Server uses a structured, type-safe error system built around two
classes: `HttpStatusException` on the server (the Kotlin exception you throw)
and `LSError` on the wire (the JSON body clients receive).  The two convert to
each other losslessly, which means the same information available in a Kotlin
`catch` block is available to a TypeScript client.

This chapter covers the error system in depth.  For a first look at `errorCases`
and `LSError` in the context of a typed endpoint, see
[Typed Endpoints](typed-endpoints.md).

## Imports

All examples in this chapter use the following imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ErrorHandlingSamples.kt#error-handling-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.typed.*
import kotlinx.serialization.*
```

Non-obvious imports:

- `com.lightningkite.lightningserver.*` brings in `HttpStatusException`, `LSError`,
  `BadRequestException`, `UnauthorizedException`, `ForbiddenException`,
  `NotFoundException`, and `toException` / `toLSError` helpers.
- `com.lightningkite.lightningserver.pathing.*` brings in `arg1` (and `arg2`, `arg3`)
  — the type-safe path-argument accessors on `ResolvedPath`.
- `com.lightningkite.lightningserver.typed.*` brings in `route`, the `Access`
  extension that converts a request's matched path into a `ResolvedPath`.
- `com.lightningkite.lightningserver.serialization.*` registers JSON (and other
  standard) media type encoders/decoders.  Required when testing through the full
  HTTP pipeline (`HttpHandler.test()`), including error response serialization.

## The Exception Hierarchy

All Lightning Server exceptions extend `HttpStatusException`.  Each subclass
maps to a fixed HTTP status code:

| Exception class | HTTP status |
|---|---|
| `BadRequestException` | 400 Bad Request |
| `UnauthorizedException` | 401 Unauthorized |
| `ForbiddenException` | 403 Forbidden |
| `NotFoundException` | 404 Not Found |
| `HttpStatusException(HttpStatus(N), ...)` | N (any status) |

The specialised subclasses exist so that call-site intent is clear and so code
readers can grep for them.  For any status outside 400 / 401 / 403 / 404, throw
`HttpStatusException` directly with the code you need.

## Throwing Exceptions

Each class has two construction forms: a **named-parameter form** for rich error
metadata, and a **single-message helper** for quick throws.

### Named-parameter form

```kotlin
// Illustrative — exception constructors are not drift-checked here;
// see core/src/main/kotlin/com/lightningkite/lightningserver/exceptions.kt for the full signatures.

// HttpStatusException for codes outside the named subclasses:
throw HttpStatusException(
    status  = HttpStatus(409),
    detail  = "conflict",          // machine-readable slug for SDK / client pattern-matching
    message = "That email address is already in use.",  // human-readable sentence
    data    = "user-456"           // data is a String; use .toString() for non-string values
)

// Subclasses use the same parameter names (no status arg — it is fixed):
throw BadRequestException(
    detail  = "invalid-format",
    message = "Email address must contain '@'."
)

throw NotFoundException(
    detail  = "user-not-found",
    message = "No user with that ID exists."
)

throw ForbiddenException(
    detail  = "insufficient-role",
    message = "Only administrators may delete accounts."
)
```

### Single-message helper

Each exception class has a companion top-level function that accepts only a
human-readable string.  These are the right choice when a `detail` slug is not
needed — quick guard assertions, internal throws that aren't exposed as API
error cases:

```kotlin
// Illustrative.
throw BadRequestException("Denominator must not be zero.")
throw UnauthorizedException("Session token is expired.")
throw NotFoundException("Requested resource was not found.")
throw ForbiddenException("You do not have permission to perform this action.")
```

The helpers set `detail = ""` so they will not appear in `errorCases`
matching (see the advisory warning section below).

## Declaring `errorCases` on a Typed Endpoint

`ApiHttpHandler` takes an `errorCases` list of `LSError` values that declare
which structured errors this endpoint may return.  These values appear in the
generated OpenAPI spec and SDK so clients can pattern-match on the `detail` slug.
The framework does **not** validate at runtime that your `throw` matches the
list — that responsibility stays with you.

The example below uses a GET endpoint with a path argument (`arg<String>`).
Inside the implementation lambda, `route.arg1` retrieves the matched path
segment as a `String`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ErrorHandlingSamples.kt#error-server -->
```kotlin
object ItemLookupServer : ServerBuilder() {

    init {
        // registerBasicMediaTypeCoders() enables JSON serialization of HTTP request/response bodies,
        // including error responses. Required when testing via HttpHandler.test() (the full HTTP pipeline).
        registerBasicMediaTypeCoders()
    }

    // In-process data — no external service needed for this example.
    private val catalog = mapOf("apple" to "A red fruit", "banana" to "A yellow fruit")

    // GET /items/{name} — returns the item description or throws a structured exception
    val getItem = path.path("items").arg<String>("name").get bind ApiHttpHandler(
        summary = "Get item by name",
        auth = noAuth,
        successCode = HttpStatus.OK,
        errorCases = listOf(
            LSError(http = 400, detail = "empty-name", message = "Item name must not be blank"),
            LSError(http = 404, detail = "item-not-found", message = "No item with that name exists")
        ),
        implementation = { _: Unit ->
            val name = route.arg1
            if (name.isBlank())
                throw BadRequestException(detail = "empty-name", message = "Item name must not be blank")
            catalog[name]
                ?: throw NotFoundException(detail = "item-not-found", message = "No item with that name exists")
        }
    )
}
```

Note that `registerBasicMediaTypeCoders()` is called in `init` so that the HTTP
pipeline can serialize error responses as JSON.  Omitting it causes test failures
when calling the raw `HttpHandler.test()` helper (see Testing below).

## The `LSError` Response Shape

When your handler throws an `HttpStatusException`, the framework's exception
handler catches it, converts it to an `LSError`, serializes it (respecting the
client's `Accept` header), and sends it as the HTTP response body.

`LSError` is a `@Serializable` data class defined in `core-shared` (available
on both JVM and generated client SDKs):

```kotlin
// Illustrative — exact source in core-shared/src/commonMain/kotlin/.../LSError.kt.
@Serializable
data class LSError(
    val http: Int,                // HTTP status code, e.g. 404
    val detail: String = "",      // machine-readable slug, e.g. "item-not-found"
    val message: String = "",     // human-readable sentence
    val data: String = "",        // optional extra context; always a String
    val stackTrace: String? = null  // only populated in debug mode (see below)
)
```

A 404 for a missing item looks like this over the wire:

```json
{
  "http": 404,
  "detail": "item-not-found",
  "message": "No item with that name exists",
  "data": ""
}
```

Clients should pattern-match on `detail` — not `message` — because messages
are human-readable and may change between releases without a breaking-change bump.

### Conversion helpers

`HttpStatusException` and `LSError` are bidirectionally convertible:

```kotlin
// Illustrative — both helpers are in com.lightningkite.lightningserver.

// Exception → LSError (used internally by the exception handler)
val lsError: LSError = exception.toLSError()

// LSError → exception (useful when receiving an error from a downstream call)
val exception: HttpStatusException = lsError.toException()

// toException() also accepts per-call message/data overrides:
val exception2: HttpStatusException = lsError.toException(message = "Custom override")

// Construct an exception directly from an LSError:
throw HttpStatusException(lsError)
```

## Debug Mode

When `generalSettings().debug` is `true`, the framework:

1. Includes the exception class name and message for **any** exception (not just
   `HttpStatusException`) rather than returning the generic "An unknown error
   occurred" used in production.
2. Populates `stackTrace` in the `LSError` body.

In production (`debug = false`), any exception that is not an
`HttpStatusException` is returned as HTTP 500 with `detail = "unknown"` and no
stack trace.  This prevents implementation details from leaking to clients.

> Keep `debug = false` in production.  The `stackTrace` field is present in the
> `LSError` schema so generated clients can read it during development, but it
> should never be populated in a deployed service.

## Testing: the Typed Test (Kotlin exception path)

`ApiHttpHandler.test()` calls the implementation lambda directly without going
through the full HTTP pipeline.  When the implementation throws an
`HttpStatusException`, the exception propagates as a normal Kotlin exception.
Catch it and inspect `e.status.code` and `e.detail`:

> To wrap these examples in a test class, annotate your test methods with
> `@Test` — see [Testing Your Server](testing.md) for the complete `@Test` +
> `testBlocking` pattern.

<!-- sample: com/lightningkite/lightningserver/guide/samples/ErrorHandlingSamples.kt#error-typed-test -->
```kotlin
fun errorTypedTest() = ItemLookupServer.testBlocking(settings = {}) {
    // ApiHttpHandler.test() calls the implementation lambda directly.
    // Thrown HttpStatusExceptions propagate as Kotlin exceptions — not as HTTP responses.
    // Catch HttpStatusException and inspect .status.code and .detail to verify the right error fired.
    try {
        ItemLookupServer.getItem.test("unknown-item", null, Unit)
        error("Expected NotFoundException")
    } catch (e: HttpStatusException) {
        check(e.status.code == 404)
        check(e.detail == "item-not-found")
    }
}
```

The `null` auth argument is correct for `noAuth` endpoints; see
[Authentication & Sessions](auth.md) for how this changes when the endpoint
requires a user token.

## Testing: the HTTP Pipeline (status-code path)

For HTTP-level testing — status codes, headers, response bodies — call the raw
`.test()` on the underlying `HttpHandler` rather than on the typed
`ApiHttpHandler`.  The `HttpHandler.test()` extension drives the full pipeline
including the exception handler, so the thrown exception is converted to an
`HttpResponse` before being returned:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ErrorHandlingSamples.kt#error-http-test -->
```kotlin
fun errorHttpTest() = ItemLookupServer.testBlocking(settings = {}) {
    // HttpHandler.test() drives the full HTTP pipeline, including the exception handler.
    // The thrown exception is converted to an HttpResponse — inspect .status.code on the result.
    // This is what real HTTP clients see: an HttpResponse with status 404 and an LSError JSON body.
    val response = ItemLookupServer.getItem.test("unknown-item")
    check(response.status.code == 404)
}
```

This test verifies the same scenario as `errorTypedTest` above but at the HTTP
level.  Both are useful: the typed test is simpler and gives you direct access to
the exception fields; the HTTP test verifies that the exception handler wiring is
correct and that the status code survives the full serialization round-trip.

## The Advisory Warning

If you throw an `HttpStatusException` with a non-blank `detail` slug that does
not appear in `errorCases`, the framework logs a warning via the
`com.lightningkite.lightningserver.typed.ApiHttpHandler` logger:

```
Endpoint threw HttpStatusException(status=404, detail="account-suspended")
not present in its declared errorCases [404:item-not-found].
Add it to errorCases or align the thrown detail so clients/docs stay accurate.
```

The exception is still rethrown and the client receives the correct error
response — the warning is advisory only.  Its purpose is to surface contract
drift between what you declared in `errorCases` and what you actually throw.
When you see it, either add the missing `LSError` to `errorCases` or change the
`detail` slug to one already in the list.

> Wart: the warning fires on any non-blank detail slug that is absent from the
> list — there is no suppression mechanism.  If a helper or interceptor throws
> with a fixed slug (e.g., an input validation interceptor), you must add that
> slug to every endpoint's `errorCases` to silence the warning, or live with
> the log noise.

## Custom Exception Handler

If the built-in exception handler does not cover your needs — for example, you
want to map `IllegalArgumentException` to 400 or include a correlation ID in
all 500 responses — implement `ExceptionHttpHandler`:

```kotlin
// Illustrative — not drift-checked.
// ExceptionHttpHandler is in com.lightningkite.lightningserver.http.
// Note: DefaultExceptionHttpHandler is internal to the framework; you cannot
// call it directly from your code. Reconstruct an HttpStatusException and
// let the framework handle it, or build the HttpResponse manually.
object CorrelatingExceptionHandler : ExceptionHttpHandler {
    context(server: ServerRuntime)
    override suspend fun handle(
        request: HttpRequest<PathSpec>,
        exception: Exception,
    ): HttpResponse {
        val correlationId = java.util.UUID.randomUUID().toString()
        val statusException = when (exception) {
            is HttpStatusException -> exception
            is IllegalArgumentException -> BadRequestException(
                detail  = "invalid-argument",
                message = exception.message ?: "Invalid argument"
            )
            else -> HttpStatusException(
                status  = HttpStatus.InternalServerError,
                detail  = "unknown",
                message = "An error occurred (id: $correlationId)"
            )
        }
        return HttpResponse(
            status = statusException.status,
            body   = statusException.toLSError().toTypedData(request.headers.accept)
        )
    }
}
```

> `DefaultExceptionHttpHandler` is `internal` to the framework — you cannot
> reference it from your own code.  If you need fall-through to the default
> behaviour, replicate the logic above: convert `HttpStatusException` via
> `.toLSError()`, return a generic 500 for everything else.
>
> Wart: there is currently no registration API for a custom `ExceptionHttpHandler`
> documented in the public guide.  Check the engine documentation for your
> deployment target (Ktor, Netty, JDK server) for the wiring point.  The
> interface itself is public and stable.

## What's Next

- **Typed Endpoints** — full `errorCases`, `successCode`, and SDK generation
  reference: [Typed Endpoints](typed-endpoints.md)
- **Authentication & Sessions** — how `ForbiddenException` (403) and
  `UnauthorizedException` (401) surface from the auth layer:
  [Authentication & Sessions](auth.md)
- **Testing** — the full `testBlocking` / `@Test` pattern:
  [Testing Your Server](testing.md)
