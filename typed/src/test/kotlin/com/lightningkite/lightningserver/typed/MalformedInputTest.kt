@file:OptIn(com.lightningkite.lightningserver.EngineApi::class)

package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.LSError
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.lightningserver.runtime.handleRoot
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** A client's malformed path argument, query parameter or body is a 400 with an [LSError], however it arrives. */
class MalformedInputTest {

    @Serializable
    data class Query(val count: Int, val id: Uuid)

    @Serializable
    data class Body(val id: Uuid)

    object TestServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
        }

        val cache = setting("cache", Cache.Settings())

        val byId = path.path("items").arg<Uuid>("id").post bind ApiHttpHandler(
            summary = "By id",
            auth = noAuth,
            implementation = { body: Body -> "${route.arg1} ${body.id}" }
        )

        val search = path.path("search").get bind ApiHttpHandler(
            summary = "Search",
            auth = noAuth,
            implementation = { query: Query -> "${query.count} ${query.id}" }
        )

        val meta = path.path("meta") include MetaEndpoints("com.lightningkite.lightningserver.typed", cache)
    }

    context(test: TestRunner<*>)
    private suspend fun root(method: HttpMethod, path: String, query: String? = null, body: String? = null): HttpResponse =
        engine.handleRoot(
            HttpRequest<PathSpec>(
                path = RawHttpEndpoint(asString = path, method = method),
                queryParameters = query?.let { QueryParameters.parse(it) } ?: QueryParameters.EMPTY,
                headers = HttpHeaders.EMPTY,
                domain = "example.com",
                protocol = "https",
                sourceIp = "local",
                body = body?.let { TypedData.text(it, MediaType.Application.Json) },
            ),
            Execution.ID.generate(),
        )

    private val id = Uuid.random()

    @Test
    fun malformedInputIsABadRequest() = TestServer.testBlocking(settings = {}) {
        assertEquals(HttpStatus.OK, root(HttpMethod.POST, "/items/$id", body = """{"id":"$id"}""").status)
        assertEquals(HttpStatus.OK, root(HttpMethod.GET, "/search", query = "count=1&id=$id").status)
        for ((name, response) in listOf(
            "path" to root(HttpMethod.POST, "/items/not-a-uuid", body = """{"id":"$id"}"""),
            "query int" to root(HttpMethod.GET, "/search", query = "count=one&id=$id"),
            "query uuid" to root(HttpMethod.GET, "/search", query = "count=1&id=not-a-uuid"),
            "body uuid" to root(HttpMethod.POST, "/items/$id", body = """{"id":"not-a-uuid"}"""),
        )) {
            assertEquals(HttpStatus.BadRequest, response.status, name)
            assertEquals(400, Json.decodeFromString<LSError>(response.body!!.text()).http, name)
        }
    }

    @Test
    fun malformedPathInABulkPartIsABadRequest() = TestServer.testBlocking(settings = {}) {
        val response = root(
            HttpMethod.POST, "/meta/bulk",
            body = """{"a":{"path":"/items/not-a-uuid","method":"POST","body":"{\"id\":\"$id\"}"}}""",
        )
        assertEquals(HttpStatus.OK, response.status)
        val part = Json.decodeFromString<Map<String, BulkResponse>>(response.body!!.text()).getValue("a")
        assertEquals(400, part.error?.http)
    }
}
