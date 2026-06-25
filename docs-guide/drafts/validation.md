> # ⚠️ UNREVIEWED FIRST DRAFT — DO NOT PUBLISH
> Auto-generated first pass. Code samples are modeled on the current source but have **NOT** been compiled, drift-checked, or reviewed. This page is intentionally **not** in the site nav. Before publishing: port samples into the drift-checked `docs-guide/src/samples` module, run the fresh-eyes judge, and delete this banner.

# Validation

Lightning Server includes a declaration-style validation system built on top of
kotlinx.serialization.  You annotate your model fields with constraints, and the
framework checks those constraints automatically every time a typed endpoint
receives input — before your implementation lambda runs.

Validation is opt-in: the `ServerBuilder.annotationValidators` property defaults
to an empty set of validators, so no constraint-checking happens unless you
enable it.

## Imports

All examples in this chapter use the following imports:

```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.data.*
import com.lightningkite.services.database.validation.*
import kotlinx.coroutines.*
import kotlinx.serialization.*
```

## How Automatic Validation Works

When a request arrives at a typed endpoint, `ApiHttpHandler` runs three steps
before it calls your implementation:

1. **Parse** — deserialise the request body (or query parameters for GET
   requests) into the typed input.
2. **Validate** — run `server.validators.assertValidOrBadRequest(inputType,
   input)` against the parsed value.
3. **Handle** — call your implementation lambda with the valid input.

If step 2 finds any constraint violations, it throws `BadRequestException` with
`detail = "validation-failed"` and the call never reaches step 3.  Your
implementation is therefore guaranteed to receive input that passes all declared
constraints.

## Enabling the Standard Validators

Override `annotationValidators` in your `ServerBuilder`:

```kotlin
object MyServer : ServerBuilder() {

    // Enables the built-in constraint annotations (@MaxLength, @IntegerRange, etc.)
    // and wires in the server's serializers module so custom serializable types
    // can participate in validation.
    override val annotationValidators: Runtime<AnnotationValidators> =
        AnnotationValidators.StandardWithExternalModule

    val database = setting("database", Database.Settings())
    // ... rest of your server
}
```

`AnnotationValidators.StandardWithExternalModule` is a `Runtime<AnnotationValidators>`
— the framework resolves it once per running server instance.  If you also need
file-type validation (via `@MimeType` on `ServerFile` fields), combine it with
`AnnotationValidators.Files`:

```kotlin
override val annotationValidators: Runtime<AnnotationValidators> = Runtime.Cached {
    AnnotationValidators.StandardWithExternalModule() + AnnotationValidators.Files()
}
```

## Built-in Validation Annotations

All annotations are from `com.lightningkite.services.data` (shipped in the
`data-shared` module of service-abstractions).

| Annotation | Applies to | Constraint |
|---|---|---|
| `@MaxLength(size)` | `String` and string-like types | String length must not exceed `size` characters |
| `@MaxSize(size)` | `List`, `Set`, `Map`, and variants | Collection must not exceed `size` elements |
| `@IntegerRange(min, max)` | `Byte`, `Short`, `Int`, `Long` | Value must be in `min..max` (inclusive) |
| `@FloatRange(min, max)` | `Float`, `Double` | Value must be in `min..max` (inclusive) |
| `@ExpectedPattern(pattern)` | `String` and string-like types | String must match the given regex pattern |
| `@MimeType(vararg types, maxSize)` | `ServerFile` | File must exist, be within `maxSize` bytes, and have a matching content type (suspending; requires `AnnotationValidators.Files()`) |

`@MaxLength` works on `String`, `TrimmedString`, `CaselessString`,
`TrimmedCaselessString`, `EmailAddress`, and `PhoneNumber` — all string-like
types in the service-abstractions `data-shared` module.  `@MaxSize` works on
`List`, `Set`, and `HashSet`.

### Example: Annotated model

```kotlin
@Serializable
data class CreateUserRequest(
    @MaxLength(50) val name: String,
    @MaxLength(254) val email: String,
    @IntegerRange(0, 120) val age: Int,
    @MaxSize(10) val tags: List<@MaxLength(20) String>,
)
```

When a `POST` request arrives at an endpoint whose input is `CreateUserRequest`,
the framework walks every field recursively.  Annotations on list elements
(`@MaxLength(20)` above) cascade into the list's elements automatically — each
`String` in `tags` is validated against the 20-character limit.

## What a Validation Failure Looks Like

When one or more constraints fail, `assertValidOrBadRequest` collects all
violations into a map keyed by dotted field path, then throws:

```kotlin
throw BadRequestException(
    detail = "validation-failed",
    message = "name: Too long; maximum 50 characters allowed, age: Out of range; expected to be between 0 and 120",
    data = """{"name":"Too long; maximum 50 characters allowed","age":"Out of range; expected to be between 0 and 120"}"""
)
```

The HTTP client receives a 400 response whose body is an `LSError`:

```json
{
  "http": 400,
  "detail": "validation-failed",
  "message": "name: Too long; maximum 50 characters allowed, age: ...",
  "data": "{\"name\":\"Too long; ...\",\"age\":\"...\"}"
}
```

`data` is a JSON-encoded `Map<String, String>` where keys are dotted paths
(`"tags.2"`, `"address.street"`, etc.) so clients can map errors back to
individual fields in a form.

## Writing a Custom Validator

`AnnotationValidators` takes a builder lambda where you register handlers for
each annotation/type pair.  Define your annotation, register a handler, and
combine it with the standard set:

```kotlin
// 1. Declare the annotation.
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@ValidationAnnotation
annotation class MustStartWith(val prefix: String)

// 2. Register a handler in your ServerBuilder.
object MyServer : ServerBuilder() {

    override val annotationValidators: Runtime<AnnotationValidators> = Runtime.Cached {
        AnnotationValidators.StandardWithExternalModule() +
        AnnotationValidators {
            // validate<AnnotationType, FieldType> { value -> "error message" or null }
            validate<MustStartWith, String> { value ->
                if (!value.startsWith(prefix)) "Must start with '$prefix'"
                else null
            }
        }
    }

    // ...
}
```

The lambda receives `this = annotation instance` and `value = field value`.
Return a non-null `String` to report an error, or `null` to pass.

For asynchronous checks (for example, checking a database for uniqueness) use
`validateSuspending`:

```kotlin
AnnotationValidators {
    validateSuspending<MustBeUnique, String> { value ->
        // suspending code is allowed here
        if (database().table<User>().count(condition { it.email eq value }) > 0)
            "Email is already registered"
        else null
    }
}
```

Suspending validators are queued and run after the synchronous pass completes,
so they do not hold up non-suspending validation.

## Calling `assertValidOrBadRequest` Manually

You can validate any serializable value yourself outside of the endpoint
pipeline — useful in task handlers or startup code:

```kotlin
// Inside a handler or task where a ServerRuntime is in context:
server.validators.assertValidOrBadRequest(CreateUserRequest.serializer(), input)
```

If validation passes the call returns normally.  If it fails, it throws
`BadRequestException` with `detail = "validation-failed"` exactly as the
automatic pipeline does.

## Testing Validation

Validation runs inside the `handle()` pipeline, so the typed `.test()` helper
triggers it.  Pass invalid input and catch `BadRequestException`:

```kotlin
fun validationTest() = MyServer.testBlocking(settings = {}) {
    try {
        MyServer.createUser.test(null, CreateUserRequest(
            name = "A".repeat(51),  // exceeds @MaxLength(50)
            email = "user@example.com",
            age = 25,
            tags = emptyList()
        ))
        error("Expected BadRequestException")
    } catch (e: BadRequestException) {
        check(e.detail == "validation-failed")
        check("name" in e.message)
    }
}
```

## What's Next

- **Error Handling & Exceptions** — how to throw structured error responses and
  how the framework converts them to `LSError` JSON for clients.
- **Typed Endpoints** — full `ApiHttpHandler` metadata including `errorCases`,
  which lets you declare validation-failure error cases in your API contract.
