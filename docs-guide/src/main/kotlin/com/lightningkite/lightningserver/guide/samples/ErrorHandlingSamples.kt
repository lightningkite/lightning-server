package com.lightningkite.lightningserver.guide.samples

// region error-handling-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.typed.*
import kotlinx.serialization.*
// endregion error-handling-imports

// region error-server
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
// endregion error-server

// region error-typed-test
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
// endregion error-typed-test

// region error-http-test
fun errorHttpTest() = ItemLookupServer.testBlocking(settings = {}) {
    // HttpHandler.test() drives the full HTTP pipeline, including the exception handler.
    // The thrown exception is converted to an HttpResponse — inspect .status.code on the result.
    // This is what real HTTP clients see: an HttpResponse with status 404 and an LSError JSON body.
    val response = ItemLookupServer.getItem.test("unknown-item")
    check(response.status.code == 404)
}
// endregion error-http-test
