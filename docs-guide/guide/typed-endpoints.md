# Typed Endpoints, Errors & SDK Generation

Chapter 1 introduced `ApiHttpHandler` for a simple echo endpoint.  This
chapter goes deeper: how to declare the full metadata a typed endpoint carries
(`summary`, `description`, `successCode`, `errorCases`), how the error system
works at both the definition and implementation level, how to write tests that
assert error responses, and how the framework turns all of this into a
generated client SDK.

## Imports

All examples in this chapter use the following imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TypedEndpointsSamples.kt#typed-imports -->
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

## Data Types

Declare input and output as `@Serializable` data classes.  The framework
infers their serialisers from the `implementation` lambda's type annotation:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TypedEndpointsSamples.kt#divide-types -->
```kotlin
@Serializable
data class DivideRequest(val numerator: Double, val denominator: Double)

@Serializable
data class DivideResponse(val result: Double)
```

## Declaring Error Cases

`ApiHttpHandler` takes four metadata fields beyond `implementation`:

- **`summary`** — one-line description used as the function name in generated
  SDKs (converted to camelCase automatically).
- **`description`** — full prose description included in OpenAPI docs.
- **`successCode`** — the HTTP status on success; defaults to `HttpStatus.OK`
  (200).
- **`errorCases`** — a list of `LSError` values declaring the structured error
  responses this endpoint may return.

`LSError` holds three fields: `http` (the numeric HTTP status code), `detail`
(a short machine-readable slug), and `message` (a human-readable sentence).
These appear in the generated OpenAPI spec and SDK so clients can pattern-match
on the `detail` slug — but they do **not** enforce anything at runtime.  Your
implementation must throw the matching exception:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TypedEndpointsSamples.kt#divide-server -->
```kotlin
object DivideServer : ServerBuilder() {

    // POST /divide — divides two numbers; declares two error cases
    val divide = path.path("divide").post bind ApiHttpHandler(
        summary = "Divide two numbers",
        description = "Returns the quotient. Rejects non-finite inputs and division by zero.",
        auth = noAuth,
        successCode = HttpStatus.OK,
        errorCases = listOf(
            // errorCases appear in the generated OpenAPI spec and SDK.
            // They do NOT enforce anything at runtime — your implementation must throw.
            LSError(http = 400, detail = "division-by-zero", message = "Denominator must not be zero"),
            LSError(http = 400, detail = "infinite-input", message = "Inputs must be finite numbers")
        ),
        implementation = { input: DivideRequest ->
            if (!input.numerator.isFinite() || !input.denominator.isFinite())
                throw BadRequestException(
                    detail = "infinite-input",
                    message = "Inputs must be finite numbers"
                )
            if (input.denominator == 0.0)
                throw BadRequestException(
                    detail = "division-by-zero",
                    message = "Denominator must not be zero"
                )
            DivideResponse(result = input.numerator / input.denominator)
        }
    )
}
```

`BadRequestException(detail, message)` produces an HTTP 400 with an `LSError`
body.  The `detail` slug in the throw must match the one declared in
`errorCases` — the framework does not validate the match, so a mismatch
silently sends an undeclared error code to the client.

The built-in exception hierarchy covers the most common cases:

| Exception | HTTP status |
|---|---|
| `BadRequestException` | 400 |
| `UnauthorizedException` | 401 |
| `ForbiddenException` | 403 |
| `NotFoundException` | 404 |
| `HttpStatusException(HttpStatus(N), ...)` | N |

For anything else, throw `HttpStatusException` directly with the code you need.

## Testing: the Success Path

The typed `.test()` extension calls `handle()` directly and returns the typed
output — no HTTP serialisation round-trip.  The first argument to
`ApiHttpHandler.test()` is the auth token; pass `null` for `noAuth` endpoints.

> To wrap these examples in a test class, annotate your test methods with `@Test` — see [Testing Your Server](testing.md) for the complete `@Test` + `runBlocking` pattern.

<!-- sample: com/lightningkite/lightningserver/guide/samples/TypedEndpointsSamples.kt#divide-success-test -->
```kotlin
fun divideSuccessTest() = runBlocking {
    DivideServer.test(settings = {}) {
        val result = DivideServer.divide.test(null, DivideRequest(10.0, 4.0))
        check(result.result == 2.5)
    }
}
```

## Testing: the Error Path

When the implementation throws an `HttpStatusException`, the typed `.test()`
propagates it directly as a Kotlin exception — the exception is not converted
to an HTTP response inside the test runner.  Catch `HttpStatusException` and
inspect `e.status.code` and `e.detail` to verify the right error fired:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TypedEndpointsSamples.kt#divide-error-test -->
```kotlin
fun divideErrorTest() = runBlocking {
    DivideServer.test(settings = {}) {
        // When an ApiHttpHandler implementation throws an HttpStatusException,
        // the typed .test() extension propagates it directly as a Kotlin exception.
        // Catch HttpStatusException and inspect .status.code and .detail to verify
        // the right error fired.
        try {
            DivideServer.divide.test(null, DivideRequest(1.0, 0.0))
            error("Expected an exception")
        } catch (e: HttpStatusException) {
            check(e.status.code == 400)
            check(e.detail == "division-by-zero")
        }
    }
}
```

This works because `ApiHttpHandler.test()` bypasses the
`DefaultExceptionHttpHandler` that normally converts exceptions to HTTP
responses in a live server.  Real HTTP clients receive a serialised `LSError`
JSON body; tests receive the raw exception.

## Custom Success Codes

`successCode` defaults to `HttpStatus.OK` (200).  Override it for create
endpoints (201 Created) or no-content responses (204 No Content):

<!-- sample: com/lightningkite/lightningserver/guide/samples/TypedEndpointsSamples.kt#success-code-types -->
```kotlin
@Serializable
data class NoteRequest(val text: String)

@Serializable
data class NoteResponse(val id: String, val text: String)
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/TypedEndpointsSamples.kt#success-code-server -->
```kotlin
object NoteServer : ServerBuilder() {

    // POST /notes — uses HttpStatus.Created (201) instead of the default 200
    val create = path.path("notes").post bind ApiHttpHandler(
        summary = "Create a note",
        description = "Stores a new note and returns it with an assigned id.",
        auth = noAuth,
        // successCode defaults to HttpStatus.OK (200); override for creation endpoints.
        successCode = HttpStatus.Created,
        errorCases = emptyList(),
        implementation = { input: NoteRequest ->
            NoteResponse(id = "note-1", text = input.text)
        }
    )
}
```

The typed `.test()` returns the output; the HTTP status is part of the HTTP
response visible to real clients but is not asserted by the typed test helper
directly.  Test the typed fields instead:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TypedEndpointsSamples.kt#success-code-test -->
```kotlin
fun successCodeTest() = runBlocking {
    NoteServer.test(settings = {}) {
        // ApiHttpHandler.test() returns the typed output directly.
        // The HTTP status code is used by real clients; in unit tests confirm
        // the response fields instead of the status.
        val result = NoteServer.create.test(null, NoteRequest("hello"))
        check(result.text == "hello")
        check(result.id.isNotEmpty())
    }
}
```

## Documentation Examples

`ApiHttpHandler` also accepts an `examples` list.  These are
documentation-only: they appear in the generated OpenAPI spec and SDK, but the
framework does not execute or assert them automatically.  Pair any example you
provide with a real test:

`EchoRequest` and `EchoResponse` are defined in Chapter 1 (`FirstEndpointSamples.kt`).

<!-- sample: com/lightningkite/lightningserver/guide/samples/TypedEndpointsSamples.kt#examples-field -->
```kotlin
object ExamplesServer : ServerBuilder() {
    // ApiHttpHandler.Example values are documentation only — they appear in the generated
    // OpenAPI spec and SDK but are NOT run or asserted automatically.
    // Write a real test alongside any example you provide.
    val echo = path.path("echo").post bind ApiHttpHandler(
        summary = "Echo",
        description = "Returns the input unchanged.",
        auth = noAuth,
        successCode = HttpStatus.OK,
        errorCases = emptyList(),
        examples = listOf(
            ApiHttpHandler.Example(
                input = EchoRequest("hello"),
                output = EchoResponse(echo = "hello", length = 5),
                name = "Basic echo",
                notes = "Showing roundtrip for a short string."
            )
        ),
        implementation = { input: EchoRequest ->
            EchoResponse(echo = input.message, length = input.message.length)
        }
    )
}
```

## Authentication

`ApiHttpHandler` takes an `auth` parameter of type `AuthRequirement`.  Chapter
1 used `noAuth` (publicly accessible endpoint, user type is `Nothing?`).  The
framework also provides `anyAuth` (any authenticated session) and — for typed
user-specific access — `PrincipalType<User, ID>.require(...)` which produces an
`AuthRequirement<User>` that the framework enforces before calling your
implementation.  Full authentication setup is covered in Chapter 6
(Authentication & Sessions); the key design point here is that `auth` is
declared at the endpoint level and is reflected in the generated SDK, so client
code can know whether a request requires a session token.

## SDK Generation

Every typed endpoint participates in SDK generation.  When you run:

```
./gradlew :your-module:run --args="sdk"
```

the framework introspects all `ApiHttpHandler` instances registered in your
`ServerBuilder`, collects their `summary`, `description`, `errorCases`,
`examples`, and type information, and writes a type-safe client library.

Two built-in formats ship with Lightning Server:

- **`FetcherSdk`** — Kotlin/Multiplatform client (suspend functions, kotlinx.serialization)
- **`TypescriptFetcherSdk`** — TypeScript client (Fetcher-based HTTP)

A minimal SDK generation call (the pattern the demo uses):

> **Note:** `KFile` is imported from `com.lightningkite.services.kfile.KFile`.

```kotlin
FetcherSdk("com.example.api").writeUsingDefaultSettings(
    Server,
    KFile("output/sdk")
)
```

`writeUsingDefaultSettings` spins up a throw-away runtime with default
settings (no external service connections), generates the SDK files, and writes
them to the specified directory.  You can call this from a CLI entry point or a
Gradle task.

> **SDK generation is not unit-assertable** in the compiled-samples system.
> Generation writes files to a directory and requires the full compiled server
> definition; there is no in-process assertion that makes sense here.  The
> command and its output format are verified manually and described accurately
> above.  If your project has a committed SDK output, a CI step that runs the
> generator and diffs the output is the appropriate correctness gate — the demo
> project does this via `./gradlew :demo:run --args="sdk"` and a git status
> check.

## What's Next

- **Services & Settings** — wire in `Database`, `Cache`, or `Files` settings
  and use them inside the `implementation` lambda.
- **Authentication & Sessions** — swap `noAuth` for
  `PrincipalType<User, ID>.require(...)` and receive a validated user object in
  the `HttpAccess` context.
- **API contract testing** — the framework ships with `apiBaselineWrite` and
  `apiCheck` commands that diff your API schema against a committed baseline and
  fail CI on breaking changes.
