# Validation

Lightning Server includes an annotation-based validation system built on top of
kotlinx.serialization. You annotate model fields with constraints, and the
framework enforces them automatically every time a typed endpoint receives input
— before your implementation lambda runs.

Validation is opt-in: `ServerBuilder.annotationValidators` defaults to an empty
set, so no constraint-checking happens unless you override it.

## Imports

All examples in this chapter use the following imports:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ValidationSamples.kt#validation-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.validation.*
import kotlinx.serialization.*
```

Non-obvious import locations:

- `com.lightningkite.lightningserver.auth.*` brings in `noAuth`
  (`AuthRequirement.None`), used to mark publicly accessible endpoints.
- `com.lightningkite.services.data.*` brings in the constraint annotations
  (`@MaxLength`, `@MaxSize`, `@IntegerRange`, `@FloatRange`, `@ExpectedPattern`)
  and also `TypedData` and `MediaType` used in HTTP-pipeline tests.
- `com.lightningkite.services.database.validation.*` brings in `AnnotationValidators`.
- `com.lightningkite.lightningserver.serialization.*` brings in
  `AnnotationValidators.StandardWithExternalModule`, `registerBasicMediaTypeCoders`,
  and the `validators` extension on `ServerRuntime`.
- `com.lightningkite.lightningserver.runtime.*` brings in `serverRuntime`, which
  gives access to the current `ServerRuntime` (including its JSON encoder) from
  within a `testBlocking {}` block.

## Annotated Model

Declare constraints directly on model fields using `@SerialInfo`-based annotations:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ValidationSamples.kt#validated-model -->
```kotlin
@Serializable
data class CreateUserRequest(
    @MaxLength(50) val name: String,
    @MaxLength(254) val email: String,
    @IntegerRange(0, 120) val age: Int,
    // @MaxSize(10) bounds the list to 10 elements; @MaxLength(20) cascades to each element.
    @MaxSize(10) @MaxLength(20) val tags: List<String>,
)
```

Placing two annotations on the same property (`@MaxSize` and `@MaxLength` on
`tags`) is how you apply constraints to both the collection and its elements.
When a `@MaxLength` annotation appears on a `List<String>` property, the
framework cascades the check into every `String` in the list — each element
must satisfy the length limit independently.

## Built-in Constraint Annotations

All annotations come from `com.lightningkite.services.data` (part of the
`data-shared` module in service-abstractions).

| Annotation | Applies to | Constraint |
|---|---|---|
| `@MaxLength(size)` | `String` and string-like types | Length must not exceed `size` characters |
| `@MaxSize(size)` | `List`, `Set`, `Map`, and variants | Must not exceed `size` elements |
| `@IntegerRange(min, max)` | `Byte`, `Short`, `Int`, `Long` | Value must be in `min..max` (inclusive) |
| `@FloatRange(min, max)` | `Float`, `Double` | Value must be in `min..max` (inclusive) |
| `@ExpectedPattern(pattern)` | `String` and string-like types | Must match the given regex |
| `@MimeType(vararg types, maxSize)` | `ServerFile` | File must match the content type and size limit (requires `AnnotationValidators.Files()`, suspending) |

`@MaxLength` works on `String`, `TrimmedString`, `CaselessString`,
`TrimmedCaselessString`, `EmailAddress`, and `PhoneNumber` — all string-like
types in the `data-shared` module.

## Enabling Validation in Your Server

Override `annotationValidators` in your `ServerBuilder`:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ValidationSamples.kt#validation-server -->
```kotlin
object ValidationServer : ServerBuilder() {
    // StandardWithExternalModule includes the built-in constraint annotations
    // (@MaxLength, @MaxSize, @IntegerRange, @FloatRange, @ExpectedPattern) and
    // includes the server's serializers module so custom serializable types
    // can also participate in validation.
    override val annotationValidators = AnnotationValidators.StandardWithExternalModule

    init {
        // Required when testing through the full HTTP pipeline (HttpHandler.test()),
        // including for validation-failure error responses.
        registerBasicMediaTypeCoders()
    }

    // POST /users — accepts a CreateUserRequest; the framework validates the input
    // against all declared constraint annotations before calling the implementation.
    val createUser = path.path("users").post bind ApiHttpHandler(
        summary = "Create a user",
        description = "Creates a new user. Constraint annotations are checked before the implementation runs.",
        auth = noAuth,
        successCode = HttpStatus.Created,
        errorCases = listOf(
            // Declare the validation-failed case so generated SDKs can pattern-match on it.
            LSError(http = 400, detail = "validation-failed", message = "Input failed constraint validation")
        ),
        implementation = { input: CreateUserRequest ->
            // This lambda only runs when every constraint passes.
            // Invalid input never reaches here — the framework rejects it with 400 first.
            "Created user: ${input.name}"
        }
    )
}
```

`AnnotationValidators.StandardWithExternalModule` is a
`Runtime<AnnotationValidators>` extension on `AnnotationValidators.Companion`
(from `com.lightningkite.lightningserver.serialization`). It builds the standard
validator set wired to the server's serializers module, so any custom
`@Serializable` types you add later can also be validated. For production use
where you also need file-type validation, combine it with `AnnotationValidators.Files()`:

```kotlin
// Illustrative — adds @MimeType file validation on top of the standard set.
override val annotationValidators = Runtime.Cached {
    AnnotationValidators.StandardWithExternalModule() + AnnotationValidators.Files()
}
```

## How Automatic Validation Works

When a typed endpoint receives a request, `ApiHttpHandler` runs three steps
before calling your implementation:

1. **Parse** — deserialise the request body (or query parameters for GET
   requests) into the typed input.
2. **Validate** — run `server.validators.assertValidOrBadRequest(inputType, input)`.
3. **Handle** — call your implementation lambda with the valid input.

If step 2 finds any violations, it throws `BadRequestException` with
`detail = "validation-failed"` and a human-readable `message` listing each
failing field. Your implementation is therefore guaranteed to receive input that
passes all declared constraints.

## What a Validation Failure Looks Like

`assertValidOrBadRequest` collects all violations into a map keyed by dotted
field path, then throws:

```kotlin
// Illustrative — exact message text comes from the annotation validator.
throw BadRequestException(
    detail = "validation-failed",
    message = "name: Too long; maximum 50 characters allowed, age: Out of range; expected to be between 0 and 120",
    data = """{"name":"Too long; maximum 50 characters allowed","age":"Out of range; expected to be between 0 and 120"}"""
)
```

HTTP clients receive a 400 response with an `LSError` body.  The `data` field
is a JSON-encoded `Map<String, String>` whose keys are dotted paths
(`"tags.2"`, `"address.street"`, etc.) so client-side form code can map errors
back to individual fields.

## Testing: the Rejection Path

> To wrap these examples in a test class, annotate your test methods with `@Test` — see [Testing Your Server](testing.md) for the complete `@Test` + `testBlocking` pattern.

The typed `ApiHttpHandler.test(auth, input)` helper calls the typed `handle`
method directly and **bypasses the validation step**. To test constraint
enforcement, use `HttpHandler.test(body = ...)` — it routes through the full
HTTP pipeline, including the validation middleware:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ValidationSamples.kt#validation-reject-test -->
```kotlin
fun validationRejectTest() = ValidationServer.testBlocking(settings = {}) {
    // Encode a CreateUserRequest whose name exceeds @MaxLength(50).
    val body = TypedData.text(
        serverRuntime.externalSerialization.json.encodeToString(
            CreateUserRequest.serializer(),
            CreateUserRequest(name = "A".repeat(51), email = "user@example.com", age = 25, tags = emptyList())
        ),
        MediaType.Application.Json
    )
    // HttpHandler.test(body = ...) drives the full HTTP pipeline, including the validation
    // step that runs before the implementation lambda.
    // The typed ApiHttpHandler.test(auth, input) helper bypasses validation — always use
    // HttpHandler.test() when testing constraint enforcement.
    val response = ValidationServer.createUser.test(body = body)
    check(response.status.code == 400)
}
```

`serverRuntime.externalSerialization.json` gives the server's configured `Json`
instance.  `TypedData.text(text, MediaType.Application.Json)` wraps the encoded
string into a typed body that the HTTP handler can parse.

`HttpHandler.test()` goes through `ServerRuntime.handle()`, which routes the
request to the endpoint, applies interceptors, runs validation, and if an
exception is thrown, converts it to an HTTP response via the exception handler.
The result is an `HttpResponse` — inspect `.status.code` to confirm 400.

## Testing: the Success Path

<!-- sample: com/lightningkite/lightningserver/guide/samples/ValidationSamples.kt#validation-pass-test -->
```kotlin
fun validationPassTest() = ValidationServer.testBlocking(settings = {}) {
    val body = TypedData.text(
        serverRuntime.externalSerialization.json.encodeToString(
            CreateUserRequest.serializer(),
            CreateUserRequest(name = "Alice", email = "alice@example.com", age = 30, tags = emptyList())
        ),
        MediaType.Application.Json
    )
    val response = ValidationServer.createUser.test(body = body)
    check(response.status.code == 201)
}
```

## Writing a Custom Validator

`AnnotationValidators` takes a builder lambda where you register handlers for
each annotation/type pair.  Define your annotation, register a handler, and
combine it with the standard set:

```kotlin
// Illustrative — not a drift-checked sample.
// 1. Declare the annotation.
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@ValidationAnnotation
annotation class MustStartWith(val prefix: String)

// 2. Register a handler alongside the standard validators.
override val annotationValidators = Runtime.Cached {
    AnnotationValidators.StandardWithExternalModule() +
    AnnotationValidators {
        // validate<AnnotationType, FieldType> { value -> "error message" or null }
        validate<MustStartWith, String> { value ->
            if (!value.startsWith(prefix)) "Must start with '$prefix'"
            else null
        }
    }
}
```

The lambda receives `this = annotation instance` and `value = field value`.
Return a non-null `String` to report an error, or `null` to pass.

For asynchronous checks (for example, checking a database for uniqueness):

```kotlin
// Illustrative.
AnnotationValidators {
    validateSuspending<MustBeUnique, String> { value ->
        if (database().table(userTable).count(condition { it.email eq value }) > 0)
            "Email is already registered"
        else null
    }
}
```

Suspending validators are queued and run after the synchronous pass, so they do
not block non-suspending checks.

## Calling `assertValidOrBadRequest` Manually

You can validate any serializable value outside the endpoint pipeline — useful
in task handlers or startup code where a `ServerRuntime` is in context:

```kotlin
// Illustrative — inside a handler or task where ServerRuntime is in context.
serverRuntime.validators.assertValidOrBadRequest(CreateUserRequest.serializer(), input)
```

`server.validators` is an extension property on `ServerRuntime` (from
`com.lightningkite.lightningserver.serialization`) that resolves the current
server's `AnnotationValidators` instance.  If validation fails, it throws
`BadRequestException(detail = "validation-failed", ...)` exactly as the
automatic pipeline does.

## What's Next

- **Error Handling & Exceptions** — how to throw structured error responses and
  how the framework converts them to `LSError` JSON for clients.
- **Typed Endpoints** — full `ApiHttpHandler` metadata including `errorCases`,
  which lets you declare the `validation-failed` error case in your API contract.
