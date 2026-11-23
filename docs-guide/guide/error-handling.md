# Error handling

To fail a request, throw an `HttpStatusException`.  The framework turns it into a response with that status and an
`LSError` JSON body, which generated clients decode back into the same fields.  Any other exception becomes a 500 that
hides its details outside debug mode.

<!-- sample: com/lightningkite/lightningserver/guide/samples/ErrorHandlingSamples.kt#error-server -->
```kotlin
@Serializable
data class SignupForm(val email: String)

object ErrorsServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    private val takenEmails = setOf("taken@example.com")

    // POST /signup
    val signup = path.path("signup").post bind ApiHttpHandler(
        summary = "Sign up",
        auth = noAuth,
        successCode = HttpStatus.Created,
        errorCases = listOf(
            LSError(http = 400, detail = "invalid-email", message = "That is not an email address"),
            LSError(http = 409, detail = "email-taken", message = "That email address is already in use"),
        ),
        implementation = { input: SignupForm ->
            if ("@" !in input.email)
                throw BadRequestException(detail = "invalid-email", message = "That is not an email address")
            if (input.email in takenEmails)
                throw HttpStatusException(
                    status = HttpStatus.Conflict,
                    detail = "email-taken",
                    message = "That email address is already in use",
                    data = input.email,
                )
            "Welcome!"
        }
    )

    // GET /busy — an error response with extra headers
    val busy = path.path("busy").get bind HttpHandler {
        throw HttpStatusException(
            status = HttpStatus.ServiceUnavailable,
            detail = "maintenance",
            message = "Down for maintenance",
            headers = HttpHeaders(HttpHeader.RetryAfter to "120"),
        )
    }
}
```

Imports used in this chapter:

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
import com.lightningkite.services.data.*
import kotlinx.serialization.*
```

## Exceptions

| Exception | Status |
|---|---|
| `BadRequestException` | 400 |
| `UnauthorizedException` | 401 |
| `ForbiddenException` | 403 |
| `NotFoundException` | 404 |
| `HttpStatusException(status = ...)` | any |

Each takes `detail`, `message`, `data`, and `cause`, all optional:

- `detail`: a short, stable, machine-readable slug (`"email-taken"`).  Clients branch on this.
- `message`: a sentence for humans.  It may change between releases, so clients shouldn't parse it.
- `data`: extra context as a string, often JSON.
- `cause`: the underlying exception, for logs.

`HttpStatusException` also takes `headers`, which are added to the error response (for example `Retry-After`).
`BadRequestException("text")` and the other one-argument forms set only the message.

## The response body

```json
{
  "http": 409,
  "detail": "email-taken",
  "message": "That email address is already in use",
  "data": "taken@example.com"
}
```

`LSError` is defined in `core-shared`, so Kotlin clients share the type.  It has a fifth field, `stackTrace`, filled
only in debug mode.  The body is encoded according to the request's `Accept` header, like any other response.

Converting between the two forms:

- `exception.toLSError()` gives the body an exception produces.
- `lsError.toException()` and `HttpStatusException(lsError)` rebuild the exception, for example to rethrow an error
  received from another Lightning Server.
- `response.toLSError()` decodes an error `HttpResponse`; if the body isn't an `LSError`, it returns one built from the
  status with `detail = "unknown"`.

## Unexpected exceptions and debug mode

An exception that isn't an `HttpStatusException` becomes a 500.  What the client sees depends on the `debug` flag in
the server's general settings ([Settings](settings.md)):

| | `detail` | `message` | `stackTrace` |
|---|---|---|---|
| `debug = false` | `"unknown"` | `"An unknown error occurred"` | absent |
| `debug = true` | the exception's class name | the exception's message | the full stack trace |

With `debug = true`, every error response, including `HttpStatusException`s, carries a stack trace.  Never enable it in
production.

There is no API for replacing the exception handler (`HttpExceptionHandler.Default`), so shape errors by what you
throw.  When a library throws its own exception, catch it and rethrow an `HttpStatusException`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ErrorHandlingSamples.kt#error-rethrow -->
```kotlin
@Serializable
data class DateInput(val text: String)

object ErrorsDateServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val parse = path.path("parse-date").post bind ApiHttpHandler(
        summary = "Parse date",
        auth = noAuth,
        errorCases = listOf(LSError(http = 400, detail = "invalid-date", message = "Not an ISO date")),
        implementation = { input: DateInput ->
            try {
                kotlinx.datetime.LocalDate.parse(input.text).toString()
            } catch (e: IllegalArgumentException) {
                throw BadRequestException(detail = "invalid-date", message = "Not an ISO date", cause = e)
            }
        }
    )
}
```

## Declaring error cases

Typed endpoints list their errors in `errorCases`, which feeds the OpenAPI spec, the docs UI, and SDK comments.  The
list is documentation; your code still has to throw.  When a typed endpoint throws a non-blank `detail` that isn't in
`errorCases` with the same status, the `com.lightningkite.lightningserver.typed.ApiHttpHandler` logger warns:

```
Endpoint threw HttpStatusException(status=404, detail="account-suspended") not present in its declared errorCases [404:item-not-found]. Add it to errorCases or align the thrown detail so clients/docs stay accurate.
```

The error still reaches the client; the warning only flags drift between the contract and the code.  Exceptions
thrown with a blank `detail` (the one-argument forms) are never checked.

## Testing errors

A typed `.test()` rethrows the exception, so assert on its fields:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ErrorHandlingSamples.kt#error-typed-test -->
```kotlin
fun errorTypedTest() = ErrorsServer.testBlocking(settings = {}) {
    try {
        ErrorsServer.signup.test(null, SignupForm("taken@example.com"))
        error("Expected a conflict")
    } catch (e: HttpStatusException) {
        check(e.status == HttpStatus.Conflict)
        check(e.detail == "email-taken")
    }
}
```

The HTTP `.test()` runs the exception handler and returns the response a client would get:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ErrorHandlingSamples.kt#error-http-test -->
```kotlin
fun errorHttpTest() = ErrorsServer.testBlocking(settings = {}) {
    val response = ErrorsServer.signup.test(
        body = TypedData.text("""{"email":"not-an-email"}""", MediaType.Application.Json)
    )
    check(response.status == HttpStatus.BadRequest)
    // Decoding the body needs a ServerRuntime, so it runs inside execute { }.
    val error = execute { response.toLSError() }
    check(error.detail == "invalid-email")

    val busy = ErrorsServer.busy.test()
    check(busy.status == HttpStatus.ServiceUnavailable)
    check(busy.headers[HttpHeader.RetryAfter]?.root == "120")
}
```

## Next

- [Validation](validation.md): input constraint failures, reported as `validation-failed`.
- [Typed endpoints](typed-endpoints.md): `errorCases` alongside the other endpoint metadata.
- [Client SDKs](client-sdks.md#calling-the-api-directly): what clients receive.
