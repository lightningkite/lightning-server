> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Error Handling & Exceptions

Lightning Server uses a structured, type-safe error system built around two classes: `HttpStatusException` on the
server (a Kotlin exception you throw) and `LSError` on the wire (the JSON body clients receive).  The two convert
to each other losslessly, which means the same information available to your Kotlin `catch` block is available to
your TypeScript client.

---

## Imports

All examples in this chapter use the following imports:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.typed.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
```

---

## The Exception Hierarchy

All Lightning Server exceptions extend `HttpStatusException`.  Each subclass maps to a specific HTTP status code:

| Exception class | HTTP status |
|---|---|
| `BadRequestException` | 400 Bad Request |
| `UnauthorizedException` | 401 Unauthorized |
| `ForbiddenException` | 403 Forbidden |
| `NotFoundException` | 404 Not Found |
| `HttpStatusException(HttpStatus(N), ...)` | N (any status) |

The specialised subclasses are thin aliases — they exist so that call-site intent is clear and so code readers
can grep for them.  For any code outside the 400/401/403/404 cases, throw `HttpStatusException` directly with
the status you need.

---

## Throwing Exceptions

Each class has two construction forms: a **named-parameter form** for rich error metadata, and a
**single-message helper** for quick throws.

### Named-parameter form

```kotlin
// Full form — all four fields are optional beyond status.
throw HttpStatusException(
    status  = HttpStatus(409),
    detail  = "conflict",          // machine-readable slug for SDK / client pattern-matching
    message = "That email address is already in use.",  // human-readable sentence
    data    = userId               // arbitrary extra context (typically a string or JSON)
)

// Convenience subclasses use the same parameter names:
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

When a `detail` slug is not needed (quick internal throws, guard assertions):

```kotlin
throw BadRequestException("Denominator must not be zero.")
throw UnauthorizedException("Session token is expired.")
throw NotFoundException("Requested resource was not found.")
throw ForbiddenException("You do not have permission to perform this action.")
```

The helper constructors are top-level functions — they set `detail = ""` and accept only the human-readable
`message` string.

---

## The `LSError` Response Shape

When your handler throws an `HttpStatusException`, the framework's `DefaultExceptionHttpHandler` catches it,
converts it to an `LSError`, serializes it (respecting the client's `Accept` header), and sends it as the
HTTP response body.

`LSError` is a `@Serializable` data class defined in `core-shared` (available on both JVM and client SDKs):

```kotlin
@Serializable
data class LSError(
    val http: Int,           // HTTP status code, e.g. 404
    val detail: String = "", // machine-readable slug, e.g. "not-found"
    val message: String = "", // human-readable sentence
    val data: String = "",   // optional extra context
    val stackTrace: String? = null  // only populated in debug mode (see below)
)
```

A 404 for a missing user looks like this over the wire:

```json
{
  "http": 404,
  "detail": "user-not-found",
  "message": "No user with that ID exists.",
  "data": ""
}
```

Clients should pattern-match on `detail` — not `message` — because messages are human-readable and may change
between releases without a breaking-change bump.

### Conversion helpers

`HttpStatusException` and `LSError` convert to each other directly:

```kotlin
// Exception → LSError (used internally by DefaultExceptionHttpHandler)
val lsError: LSError = exception.toLSError()

// LSError → exception (useful when you receive an error from a downstream call)
val exception: HttpStatusException = lsError.toException()

// toException() also accepts per-call overrides:
val exception2: HttpStatusException = lsError.toException(message = "Custom override")

// Constructing an exception from an LSError directly:
throw HttpStatusException(lsError)
```

---

## Debug Mode

When `generalSettings().debug` is `true`, the framework:

1. includes the exception class name and message in the response even for unexpected (non-`HttpStatusException`)
   exceptions (instead of the generic "An unknown error occurred" message used in production).
2. populates `stackTrace` in the `LSError` body.

In production (`debug = false`), any exception that is not an `HttpStatusException` is returned as a generic
500 with `detail = "unknown"` and no stack trace.  This prevents implementation details from leaking to clients.

> Keep `debug = false` in production.  The `stackTrace` field is present in the `LSError` schema so generated
> clients can read it during development, but should never be populated in a deployed service.

---

## Declaring `errorCases` on Typed Endpoints

`ApiHttpHandler` takes an `errorCases` list.  These are `LSError` values that declare which structured errors
this endpoint may return:

```kotlin
val getUser = path.path("users").arg<String>("id").get bind ApiHttpHandler(
    summary     = "Get user by ID",
    description = "Returns the user record for the given ID.",
    auth        = noAuth,
    successCode = HttpStatus.OK,
    errorCases  = listOf(
        LSError(http = 404, detail = "user-not-found", message = "No user with that ID exists.")
    ),
    implementation = { _: Unit ->
        val id = path.arg1
        database().table<User>().get(Uuid.parse(id))
            ?: throw NotFoundException(detail = "user-not-found", message = "No user with that ID exists.")
    }
)
```

`errorCases` are **documentation metadata only** — they appear in the generated OpenAPI spec and client SDK
so callers know which `detail` slugs to handle.  The framework does **not** validate at runtime that your
`throw` matches the list.  That responsibility stays with you.

### The advisory log warning

If you throw an `HttpStatusException` with a non-blank `detail` slug that does not appear in `errorCases`,
the framework logs a warning via the `com.lightningkite.lightningserver.typed.ApiHttpHandler` logger:

```
WARN  Endpoint threw HttpStatusException(status=404, detail="account-suspended") not present in its
      declared errorCases [404:user-not-found]. Add it to errorCases or align the thrown detail so
      clients/docs stay accurate.
```

The exception is still rethrown and the client receives the correct error response — the warning is advisory
only.  Its purpose is to surface contract drift between what you declared and what you actually throw.  When
you see this warning, either add the missing `LSError` to `errorCases` or change the `detail` slug to one
already in the list.

---

## Testing Error Paths

Inside a `testBlocking {}` block, `ApiHttpHandler.test()` runs the implementation lambda directly.  When the
implementation throws an `HttpStatusException`, the exception propagates as-is — it is **not** converted to
an HTTP response inside the test runner.  Catch it and inspect `e.status.code` and `e.detail`:

```kotlin
fun getUserNotFoundTest() = MyServer.testBlocking(settings = {}) {
    try {
        MyServer.getUser.test(null, Unit)   // no matching record seeded
        error("Expected an exception")
    } catch (e: HttpStatusException) {
        check(e.status.code == 404)
        check(e.detail == "user-not-found")
    }
}
```

> This propagation behaviour differs from what a real HTTP client sees.  Real clients receive a serialised
> `LSError` JSON body.  The `.test()` helper skips the `DefaultExceptionHttpHandler` conversion, so you
> assert the Kotlin exception directly.

For HTTP-level testing (status codes, headers, body bytes), call the raw `.test()` on the underlying
`HttpHandler` and inspect the response instead.

---

## Custom Exception Handler

If the built-in `DefaultExceptionHttpHandler` does not cover your needs (for example, you want to map
`IllegalArgumentException` to 400, or include a correlation ID in all 500 responses), you can provide a
custom `ExceptionHttpHandler`:

```kotlin
// Illustrative — not drift-checked.
object MyExceptionHandler : ExceptionHttpHandler {
    context(server: ServerRuntime)
    override suspend fun handle(
        request: HttpRequest<PathSpec>,
        exception: Exception,
    ): HttpResponse = when (exception) {
        is IllegalArgumentException -> HttpStatusException(
            status  = HttpStatus.BadRequest,
            detail  = "invalid-argument",
            message = exception.message ?: "Invalid argument"
        ).let { DefaultExceptionHttpHandler.handle(request, it) }

        else -> DefaultExceptionHttpHandler.handle(request, exception)
    }
}
```

Wire it up when building your server definition (the exact API for registering a custom handler is not yet
covered in this guide — consult the engine documentation for your target platform).

---

## What's Next

- **Typed Endpoints** — full `errorCases` and `successCode` reference: [Typed Endpoints](../guide/typed-endpoints.md)
- **Validation** — how input validation errors surface as 400 responses: [Validation](validation.md)
- **Testing** — the full `testBlocking` / `@Test` pattern: [Testing Your Server](../guide/testing.md)
