package com.lightningkite.lightningserver.guide.samples

// region bulk-imports
import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.builder.*
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.runtime.test.*
import com.lightningkite.lightningserver.serialization.*
import com.lightningkite.lightningserver.typed.*
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Database
// endregion bulk-imports

// region bulk-server
object BulkServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())

    val ping = path.path("ping").get bind ApiHttpHandler(
        summary = "Ping",
        auth = noAuth,
        implementation = { _: Unit -> "pong" }
    )

    val missing = path.path("missing").get bind ApiHttpHandler<_, _, Unit, String>(
        summary = "Missing",
        auth = noAuth,
        implementation = { _: Unit -> throw NotFoundException("not here") }
    )

    // POST /meta/bulk, among the other meta endpoints
    val meta = path.path("meta") include MetaEndpoints(
        packageName = "com.example.api",
        cache = cache,
    )
}
// endregion bulk-server

// region bulk-test
fun bulkTest() = BulkServer.testBlocking(settings = {}) {
    val results = BulkServer.meta.bulk.test(
        null,
        mapOf(
            "ping" to BulkRequest(path = "/ping", method = "GET"),
            "gone" to BulkRequest(path = "/missing", method = "GET"),
        )
    )

    check(results.getValue("ping").result == "\"pong\"")
    check(results.getValue("gone").error?.http == 404)
}
// endregion bulk-test

// region funnel-server
object FunnelServer : ServerBuilder() {
    init { registerBasicMediaTypeCoders() }

    val database = setting("database", Database.Settings())

    // Who may read summaries and instances.  Recording steps is open to everyone.
    val funnels = path.path("funnels") include FunnelEndpoints(database, read = AuthRequirement.IsAdmin)
}
// endregion funnel-server

// region funnel-test
fun funnelTest() = FunnelServer.testBlocking(settings = {}) {
    val id = FunnelServer.funnels.start.test(
        null,
        FunnelStart(funnel = "checkout", userAgent = "test", version = "1.0.0"),
    )
    FunnelServer.funnels.step.test(id, null, 1)
    FunnelServer.funnels.success.test(id, null, Unit)
}
// endregion funnel-test
