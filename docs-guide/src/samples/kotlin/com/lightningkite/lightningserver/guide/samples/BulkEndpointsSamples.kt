package com.lightningkite.lightningserver.guide.samples

// region bulk-imports
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Database
import kotlinx.serialization.json.Json
// endregion bulk-imports

// region bulk-server
object BulkServer : ServerBuilder() {
    init {
        // registerBasicMediaTypeCoders() enables JSON body parsing that the bulk endpoint
        // needs to deserialise the incoming Map<String, BulkRequest> and serialise the
        // Map<String, BulkResponse> it returns.
        registerBasicMediaTypeCoders()
    }

    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    // GET /ping — always succeeds; used to verify a successful sub-request in tests.
    val ping = path.path("ping").get bind ApiHttpHandler(
        summary = "Ping",
        auth = noAuth,
        implementation = { _: Unit -> "pong" }
    )

    // GET /missing — always throws; used to verify a failed sub-request in tests.
    val missing = path.path("missing").get bind ApiHttpHandler<_, _, Unit, String>(
        summary = "Missing",
        auth = noAuth,
        implementation = { _: Unit -> throw NotFoundException("not here") }
    )

    // POST /meta/bulk — accepts Map<String, BulkRequest>, fans out in parallel,
    // returns Map<String, BulkResponse>.  Always HTTP 200; per-sub-request errors
    // land in the body rather than as top-level HTTP status codes.
    val meta = path.path("meta") include MetaEndpoints(
        packageName = "com.example.guide",
        database = database,
        cache = cache,
    )
}
// endregion bulk-server

// region bulk-test
fun bulkTest() = BulkServer.testBlocking(settings = {}) {
    // Drive /meta/bulk through the full HTTP pipeline so the framework can resolve
    // sub-request paths via the registered route table.  ApiHttpHandler.test() would
    // bypass routing and cannot match sub-request paths, so we use serverRuntime.handle().
    val response = serverRuntime.handle(
        HttpRequest<PathSpec>(
            path = RawHttpEndpoint(asString = "/meta/bulk", method = HttpMethod.POST),
            queryParameters = QueryParameters.EMPTY,
            headers = HttpHeaders.EMPTY,
            domain = "example.com",
            protocol = "https",
            sourceIp = "local",
            body = TypedData.text(
                """{"ping":{"path":"/ping","method":"GET"},"gone":{"path":"/missing","method":"GET"}}""",
                MediaType.Application.Json,
            ),
        )
    )

    // The outer bulk endpoint always returns HTTP 200; per-sub-request errors appear in the body.
    check(response.status.code == 200)

    val body = response.body!!.text()
    val results = Json { ignoreUnknownKeys = true }
        .decodeFromString<Map<String, BulkResponse>>(body)

    // Successful sub-request: result holds the JSON-encoded response body, error is null.
    val ping = results["ping"]!!
    check(ping.result != null)
    check(ping.error == null)

    // Failed sub-request: error carries the HTTP status (404); result is null.
    val gone = results["gone"]!!
    check(gone.error != null)
    check(gone.error!!.http == 404)
    check(gone.result == null)
}
// endregion bulk-test
