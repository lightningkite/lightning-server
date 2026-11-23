# Typed endpoints

`ApiHttpHandler` is an `HttpHandler` with a declared input type, output type, and auth requirement.  The framework
decodes the input, validates it, checks auth, runs your lambda, and encodes the output.  Because everything is
declared, the endpoint also shows up in the OpenAPI spec, the docs UI, and generated [client SDKs](client-sdks.md).
Use it for every JSON API endpoint.

<!-- sample: com/lightningkite/lightningserver/guide/samples/TypedEndpointsSamples.kt#typed-minimal -->
```kotlin
@Serializable
data class DivisionInput(val numerator: Double, val denominator: Double)

@Serializable
data class DivisionResult(val quotient: Double)

object TypedDivideServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    // POST /divide
    val divide = path.path("divide").post bind ApiHttpHandler(
        summary = "Divide two numbers",
        description = "Returns numerator / denominator.",
        auth = noAuth,
        errorCases = listOf(
            LSError(http = 400, detail = "division-by-zero", message = "Denominator must not be zero"),
        ),
        implementation = { input: DivisionInput ->
            if (input.denominator == 0.0)
                throw BadRequestException(detail = "division-by-zero", message = "Denominator must not be zero")
            DivisionResult(input.numerator / input.denominator)
        }
    )
}
```

The input and output types are inferred from the lambda (`input: DivisionInput`) and its result.
`registerBasicMediaTypeCoders()` is required: without registered coders the endpoint can't decode or encode anything
(see [Serialization](serialization.md)).

<!-- sample: com/lightningkite/lightningserver/guide/samples/TypedEndpointsSamples.kt#typed-minimal-test -->
```kotlin
fun typedMinimalTest() = TypedDivideServer.testBlocking(settings = {}) {
    check(TypedDivideServer.divide.test(null, DivisionInput(10.0, 4.0)).quotient == 2.5)
}
```

Imports used in this chapter:

<!-- sample: com/lightningkite/lightningserver/guide/samples/TypedEndpointsSamples.kt#typed-imports -->
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

## Inputs, outputs, and path arguments

<!-- sample: com/lightningkite/lightningserver/guide/samples/TypedEndpointsSamples.kt#typed-catalog -->
```kotlin
@Serializable
data class CatalogItem(val id: Int, val name: String)

@Serializable
data class CatalogSearch(val text: String = "", val limit: Int = 10)

@Serializable
data class NewCatalogItem(val name: String)

object TypedCatalogServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    private val products = listOf(CatalogItem(1, "Lamp"), CatalogItem(2, "Desk lamp"), CatalogItem(3, "Chair"))

    // GET /products?text=lamp&limit=5 — a GET's input comes from the query string
    val search = path.path("products").get bind ApiHttpHandler(
        summary = "Search products",
        auth = noAuth,
        implementation = { input: CatalogSearch ->
            products.filter { it.name.contains(input.text, ignoreCase = true) }.take(input.limit)
        }
    )

    // GET /products/{id}
    val product = path.path("products").arg<Int>("id").get bind ApiHttpHandler(
        summary = "Get product",
        auth = noAuth,
        errorCases = listOf(LSError(http = 404, detail = "product-not-found", message = "No product with that id")),
        implementation = { _: Unit ->
            products.find { it.id == route.arg1 }
                ?: throw NotFoundException(detail = "product-not-found", message = "No product with that id")
        }
    )

    // POST /products
    val create = path.path("products").post bind ApiHttpHandler(
        summary = "Create product",
        auth = noAuth,
        successCode = HttpStatus.Created,
        implementation = { input: NewCatalogItem -> CatalogItem(id = 4, name = input.name) }
    )

    // GET /products/{id}/name — calls another endpoint, with its validation, auth, and error handling
    val productName = path.path("products").arg<Int>("id").path("name").get bind ApiHttpHandler(
        summary = "Get product name",
        auth = noAuth,
        implementation = { _: Unit -> product(request, route.arg1, Unit).name }
    )
}
```

- **GET and HEAD** decode the input from the query string (`?text=lamp&limit=5`), so give every field a default or
  require it in the URL.  Other methods decode it from the body using its `Content-Type`.
- **`Unit` input** means no body.  A nullable input type accepts a missing body as `null`.
- **`Unit` output** sends no body, with the `successCode` status.
- **Path arguments** are read with `route.arg1`, `route.arg2`, `route.arg3` inside the lambda.  `request` is the raw
  `HttpRequest`, for headers and the like.
- The response always carries `Vary: Accept`, because the encoding follows the client's `Accept` header.

### Calling another endpoint

Invoke an endpoint like a function, passing the current `request`, its path arguments, and the input (see
`productName` above).  The call goes through the target's validation, auth requirement, and execution interceptors, as
a separate traced call; HTTP interceptors don't run again.  Prefer it to calling shared business logic when the target's
checks matter.

## Endpoint metadata

| Parameter | Default | Purpose |
|---|---|---|
| `summary` | required | Short title.  Its camelCase form is the SDK function name (`"Get product"` becomes `getProduct`) |
| `functionName` | `summary.functionCase()` | Override the SDK function name without changing the summary |
| `description` | `""` | Longer prose for the docs and SDK comments |
| `auth` | required | `noAuth`, `anyAuth`, or a principal's requirement such as `User.require()` |
| `successCode` | `HttpStatus.OK` | Status of a successful response, such as `HttpStatus.Created` |
| `errorCases` | `emptyList()` | The `LSError`s this endpoint can return, for docs and clients |
| `examples` | `emptyList()` | Example input/output pairs for the docs |
| `timeout` | 30 seconds | Same as `HttpHandler`'s timeout |

Keep `summary` stable once clients exist: renaming it renames the generated function.

### Error cases

`errorCases` documents errors; it doesn't produce them.  Throw an `HttpStatusException` (`BadRequestException`,
`NotFoundException`, ...) whose `detail` matches a declared case.  If an endpoint throws a non-blank `detail` that
isn't declared with the same status, the framework logs a warning so the contract and the code don't drift apart.
See [Error handling](error-handling.md).

### Examples

<!-- sample: com/lightningkite/lightningserver/guide/samples/TypedEndpointsSamples.kt#typed-examples -->
```kotlin
object TypedExamplesServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val divide = path.path("divide").post bind ApiHttpHandler(
        summary = "Divide two numbers",
        auth = noAuth,
        examples = listOf(
            ApiHttpHandler.Example(
                input = DivisionInput(10.0, 4.0),
                output = DivisionResult(2.5),
                name = "Fractional result",
            )
        ),
        implementation = { input: DivisionInput -> DivisionResult(input.numerator / input.denominator) }
    )
}
```

Examples appear in the OpenAPI spec and the docs UI.  They are not run, so test the same case separately.

## Authentication

`auth` decides who may call the endpoint, and its type decides what the lambda sees.  With `noAuth` the caller is
anonymous.  With a requirement for a principal type, the framework rejects unauthenticated calls before your lambda
runs, and the lambda reads the caller from `auth` (`auth.id`, or `auth.fetch()` to load the record).  The requirement is
also written into the docs and SDK comments.  Setting up principals and sessions is covered in
[Authentication](auth.md).

## Testing

Typed endpoints have two kinds of `.test()`:

- **Typed**: `endpoint.test(pathArgs..., auth, input)` returns the output.  It runs validation, the auth check, and
  execution interceptors, but no HTTP encoding, and a thrown `HttpStatusException` reaches the test as an exception.
  Pass `null` as the auth for `noAuth` endpoints.
- **HTTP**: `endpoint.test(pathArgs..., queryParameters = ..., headers = ..., body = ...)` (the `HttpHandler` version)
  runs the full pipeline and returns the `HttpResponse`: status, headers, encoded body, and error responses.

<!-- sample: com/lightningkite/lightningserver/guide/samples/TypedEndpointsSamples.kt#typed-catalog-test -->
```kotlin
fun typedCatalogTest() = TypedCatalogServer.testBlocking(settings = {}) {
    // Typed calls: path arguments first, then auth (null for noAuth), then input.
    check(TypedCatalogServer.search.test(null, CatalogSearch(text = "lamp")).size == 2)
    check(TypedCatalogServer.product.test(3, null, Unit).name == "Chair")
    check(TypedCatalogServer.productName.test(1, null, Unit) == "Lamp")

    // HTTP calls: what a client sees, including the status and the encoded body.
    val found = TypedCatalogServer.search.test(queryParameters = QueryParameters.parse("text=desk"))
    check(found.body?.text() == """[{"id":2,"name":"Desk lamp"}]""")

    val created = TypedCatalogServer.create.test(body = TypedData.text("""{"name":"Stool"}""", MediaType.Application.Json))
    check(created.status == HttpStatus.Created)
}
```

<!-- sample: com/lightningkite/lightningserver/guide/samples/TypedEndpointsSamples.kt#typed-error-test -->
```kotlin
fun typedErrorTest() = TypedCatalogServer.testBlocking(settings = {}) {
    try {
        TypedCatalogServer.product.test(99, null, Unit)
        error("Expected NotFoundException")
    } catch (e: HttpStatusException) {
        check(e.status == HttpStatus.NotFound)
        check(e.detail == "product-not-found")
    }

    check(TypedCatalogServer.product.test(99).status == HttpStatus.NotFound)
}
```

## Next

- [Error handling](error-handling.md): exceptions, `LSError`, and debug mode.
- [Validation](validation.md): constraint annotations checked before your lambda runs.
- [Client SDKs](client-sdks.md): generated Kotlin and TypeScript clients.
- [Meta and admin](meta-admin.md): OpenAPI, the docs UI, and health endpoints.
- [Model REST](model-rest.md): full CRUD endpoints for a database model.
