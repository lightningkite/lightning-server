package com.lightningkite.lightningserver.typed.jsonschema

import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.services.data.MediaType
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Verifies that [openApiDescription] documents declared error cases as responses and emits path
 * arguments as `in: path` parameters.
 */
class OpenApiTest {

    object TestServer : ServerBuilder() {
        init { registerBasicMediaTypeCoders() }

        // Endpoint with a path argument and two declared error cases, one of them sharing a status code.
        val getItem = path.path("items").arg<String>("id").get bind ApiHttpHandler(
            summary = "Get Item",
            auth = noAuth,
            errorCases = listOf(
                LSError(http = 404, detail = "not-found", message = "No such item"),
                LSError(http = 400, detail = "bad-id", message = "Malformed id"),
                LSError(http = 400, detail = "blocked", message = "Access blocked"),
            ),
            implementation = { _: Unit -> "value" }
        )

        // Zero-argument endpoint to prove nothing breaks without path arguments.
        val root = path.get bind ApiHttpHandler(
            summary = "Root",
            auth = noAuth,
            implementation = { _: Unit -> "ok" }
        )
    }

    @Test
    fun errorCasesAppearAsResponses() = runBlocking {
        TestServer.test({}) {
            val op = openApiDescription.paths.entries.first { it.key.contains("items") }.value.get
            assertNotNull(op)

            // Success response is still present.
            assertTrue(op.responses.containsKey("200"), "success response should remain")

            // Declared error statuses are documented with the LSError schema and an example.
            val notFound = op.responses["404"]
            assertNotNull(notFound, "404 error case should be documented")
            val notFoundMedia = notFound.content[MediaType.Application.Json.toString()]
            assertNotNull(notFoundMedia)
            assertNotNull(notFoundMedia.schema.ref, "error response should reference the LSError schema")

            // The two 400 cases are grouped into a single response with a combined description.
            val badRequest = op.responses["400"]
            assertNotNull(badRequest, "400 error cases should be documented")
            assertTrue(badRequest.description.contains("bad-id"))
            assertTrue(badRequest.description.contains("blocked"))
        }
    }

    @Test
    fun pathArgumentsAppearAsParameters() = runBlocking {
        TestServer.test({}) {
            val paths = openApiDescription.paths

            val itemsPath = paths.entries.first { it.key.contains("items") }.value
            val idParam = itemsPath.parameters.singleOrNull { it.name == "id" }
            assertNotNull(idParam, "path argument should be emitted as a parameter")
            assertEquals(OpenApiParameterType.path, idParam.inside)
            assertTrue(idParam.required, "path parameters must be required")

            // Zero-argument endpoints emit no path parameters.
            val rootPath = paths.entries.first { it.key == "/" || it.key.isEmpty() }.value
            assertTrue(rootPath.parameters.none { it.inside == OpenApiParameterType.path })
        }
    }
}
