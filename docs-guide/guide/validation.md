# Validation

Annotate fields of your input types with constraints, and typed endpoints reject input that breaks them with a 400
before your code runs.  Validation is off until the server sets `annotationValidators`.

<!-- sample: com/lightningkite/lightningserver/guide/samples/ValidationSamples.kt#validation-server -->
```kotlin
@Serializable
data class NewAccount(
    @MaxLength(50) val name: String,
    @ExpectedPattern("[a-z0-9_]{3,20}") val handle: String,
    @IntegerRange(13, 120) val age: Int,
    // @MaxSize limits the list; @MaxLength applies to each element.
    @MaxSize(10) @MaxLength(20) val tags: List<String> = emptyList(),
)

object ValidatedServer : ServerBuilder() {
    override val annotationValidators = AnnotationValidators.StandardWithExternalModule

    init { registerBasicMediaTypeCoders() }

    val createAccount = path.path("accounts").post bind ApiHttpHandler(
        summary = "Create account",
        auth = noAuth,
        successCode = HttpStatus.Created,
        errorCases = listOf(LSError(http = 400, detail = "validation-failed", message = "Input failed validation")),
        implementation = { input: NewAccount -> "Created ${input.handle}" }
    )
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/ValidationSamples.kt#validation-test -->
```kotlin
fun validationTest() = ValidatedServer.testBlocking(settings = {}) {
    val valid = NewAccount(name = "Alice", handle = "alice", age = 30)
    check(ValidatedServer.createAccount.test(null, valid) == "Created alice")

    try {
        ValidatedServer.createAccount.test(null, valid.copy(handle = "A!", age = 7))
        error("Expected validation to fail")
    } catch (e: BadRequestException) {
        check(e.detail == "validation-failed")
        check("handle" in e.data && "age" in e.data)
    }
}
```

Imports used in this chapter:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ValidationSamples.kt#validation-imports -->
```kotlin
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.*
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

## Built-in constraints

The annotations live in `com.lightningkite.services.data`.

| Annotation | Applies to | Rule |
|---|---|---|
| `@MaxLength(size)` | `String` and string types (`TrimmedString`, `CaselessString`, `EmailAddress`, ...) | At most `size` characters |
| `@MaxSize(size)` | `List`, `Set`, `Map` | At most `size` elements |
| `@ExpectedPattern(pattern)` | `String` and string types | Matches the whole regex |
| `@IntegerRange(min, max)` | `Byte`, `Short`, `Int`, `Long` | In `min..max` |
| `@FloatRange(min, max)` | `Float`, `Double` | In `min..max` |
| `@MimeType(vararg whitelist, blacklist, maxSize)` | `ServerFile` | The uploaded file's type and size; needs `AnnotationValidators.Files` from the `files` module |

On a collection, an annotation that doesn't fit the collection applies to its elements: `@MaxSize(10)
@MaxLength(20)` on a `List<String>` limits the list to 10 entries of at most 20 characters each.  Nested
`@Serializable` objects are validated too.

Prefer a type that can't hold bad values (`EmailAddress`, `PhoneNumber`) over a pattern on a `String`.

## Enabling validation

Override `annotationValidators` on the server:

- `AnnotationValidators.StandardWithExternalModule` is the built-in set above, using the server's `externalSerialization` module
  (the one client input is decoded with) so your own contextual types can be validated.  `StandardWithInternalModule`
  uses `internalSerialization` instead.
- Combine sets with `+`.  For file validation (with the `files` module on the classpath):

```kotlin
override val annotationValidators = Runtime.Cached {
    AnnotationValidators.StandardWithExternalModule() + AnnotationValidators.Files()
}
```

## The failure response

Every violation is collected before the request is rejected with `BadRequestException`:

- `detail` is `"validation-failed"`.
- `message` lists each failing field: `handle: Does not match pattern; expected to match [a-z0-9_]{3,20}, age: Out of
  range; expected to be between 13 and 120`.
- `data` is a JSON object from dotted field path (`"tags.2"`, `"address.street"`) to message, so a client can show each
  error next to its field.

Declare the `validation-failed` case in `errorCases` of endpoints that validate, so clients know to expect it.

Over HTTP the same failure is a 400 with an [`LSError`](error-handling.md#the-response-body) body:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ValidationSamples.kt#validation-http-test -->
```kotlin
fun validationHttpTest() = ValidatedServer.testBlocking(settings = {}) {
    val response = ValidatedServer.createAccount.test(
        body = TypedData.text("""{"name":"Bob","handle":"bob","age":200}""", MediaType.Application.Json)
    )
    check(response.status == HttpStatus.BadRequest)
}
```

## Custom constraints

Declare an annotation the same way the built-in ones are declared, then register a check for each type it applies to:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ValidationSamples.kt#validation-custom -->
```kotlin
@SerialInfo
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@ValidationAnnotation
annotation class MustStartWith(val prefix: String)

@Serializable
data class Coupon(@MustStartWith("SAVE") val code: String)

object CouponServer : ServerBuilder() {
    override val annotationValidators = Runtime.Cached {
        AnnotationValidators.StandardWithExternalModule() + AnnotationValidators {
            // `this` is the annotation; return an error message, or null when the value is valid.
            validate<MustStartWith, String> { value ->
                if (value.startsWith(prefix)) null else "Must start with '$prefix'"
            }
        }
    }

    init { registerBasicMediaTypeCoders() }

    val redeem = path.path("redeem").post bind ApiHttpHandler(
        summary = "Redeem coupon",
        auth = noAuth,
        implementation = { input: Coupon -> "Redeemed ${input.code}" }
    )
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/ValidationSamples.kt#validation-custom-test -->
```kotlin
fun customValidationTest() = CouponServer.testBlocking(settings = {}) {
    check(CouponServer.redeem.test(null, Coupon("SAVE10")) == "Redeemed SAVE10")
    try {
        CouponServer.redeem.test(null, Coupon("FREE"))
        error("Expected validation to fail")
    } catch (e: BadRequestException) {
        check(e.message == "code: Must start with 'SAVE'")
    }
}
```

Inside `AnnotationValidators { }`:

- `validate<Annotation, Type> { value -> ... }` registers a synchronous check.
- `validateStrings<Annotation> { }` registers it for `String` and all the string types at once; `validateCollections` does
  the same for lists and sets.
- `validateSuspending<Annotation, Type> { value -> ... }` registers a check that may suspend, such as a database lookup.
  Suspending checks run after all the synchronous ones.

## Validating outside an endpoint

Typed endpoints validate automatically.  Anywhere else with a `ServerRuntime` in context (a raw handler, a task), call
the same check yourself:

<!-- sample: com/lightningkite/lightningserver/guide/samples/ValidationSamples.kt#validation-manual -->
```kotlin
object ImportServer : ServerBuilder() {
    override val annotationValidators = AnnotationValidators.StandardWithExternalModule

    init { registerBasicMediaTypeCoders() }

    val import = path.path("import").post bind HttpHandler { request ->
        val account = request.body?.parse(NewAccount.serializer()) ?: throw BadRequestException("Missing body")
        serverRuntime.validators.assertValidOrBadRequest(NewAccount.serializer(), account)
        HttpResponse.plainText("Imported ${account.handle}")
    }
}
```

It throws the same `validation-failed` `BadRequestException`, so the client gets the same 400.

## Next

- [Error handling](error-handling.md): how the 400 reaches the client.
- [Serialization](serialization.md): serializers modules and contextual types.
- [Files](files.md): uploads and `@MimeType`.
