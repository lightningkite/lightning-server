package com.lightningkite.lightningserver.guide.samples

// region typed-imports
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
// endregion typed-imports

// region typed-minimal
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
// endregion typed-minimal

// region typed-minimal-test
fun typedMinimalTest() = TypedDivideServer.testBlocking(settings = {}) {
    check(TypedDivideServer.divide.test(null, DivisionInput(10.0, 4.0)).quotient == 2.5)
}
// endregion typed-minimal-test

// region typed-catalog
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
// endregion typed-catalog

// region typed-catalog-test
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
// endregion typed-catalog-test

// region typed-error-test
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
// endregion typed-error-test

// region typed-examples
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
// endregion typed-examples
