package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.handleSubRequest
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Database
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `/meta/bulk` used to invoke each sub-request's handler directly, so sub-requests bypassed the
 * entire interceptor chain — access logging, auditing and rate limiting among them. N logical
 * requests executed while the pipeline saw one. These tests pin the fixed behaviour: a
 * [HttpLogicalInterceptor] observes every sub-request, a [HttpConnectionInterceptor] still observes
 * only the physical request, and each sub-request is independently attributable via its own
 * request ID.
 */
class BulkInterceptorScopeTest {

    private data class Seen(val path: String, val requestId: String, val parentRequestId: String?)

    private object Observed {
        val logical: MutableList<Seen> = Collections.synchronizedList(mutableListOf())
        val connection: MutableList<Seen> = Collections.synchronizedList(mutableListOf())

        fun reset() {
            logical.clear()
            connection.clear()
        }
    }

    /** Records what it saw, then delegates. Shared by both interceptor kinds below. */
    private fun record(into: MutableList<Seen>, request: HttpRequest<*>) {
        into.add(Seen("/" + request.path.pathSegments.toString(), request.requestId, request.parentRequestId))
    }

    private inner class LogicalRecorder : HttpLogicalInterceptor {
        override val name: String = "LogicalRecorder"

        context(runtime: ServerRuntime)
        override suspend fun intercept(
            request: HttpRequest<*>,
            cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse,
        ): HttpResponse {
            record(Observed.logical, request)
            return cont(request)
        }
    }

    private inner class ConnectionRecorder : HttpConnectionInterceptor {
        override val name: String = "ConnectionRecorder"

        context(runtime: ServerRuntime)
        override suspend fun intercept(
            request: HttpRequest<*>,
            cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse,
        ): HttpResponse {
            record(Observed.connection, request)
            return cont(request)
        }
    }

    object TestServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
            install(BulkInterceptorScopeTest().LogicalRecorder())
            install(BulkInterceptorScopeTest().ConnectionRecorder())
        }

        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())

        val alpha = path.path("alpha").get bind ApiHttpHandler(
            summary = "Alpha",
            auth = noAuth,
            implementation = { _: Unit -> "alpha" }
        )

        val beta = path.path("beta").get bind ApiHttpHandler(
            summary = "Beta",
            auth = noAuth,
            implementation = { _: Unit -> "beta" }
        )

        val boom = path.path("boom").get bind ApiHttpHandler<_, _, Unit, String>(
            summary = "Boom",
            auth = noAuth,
            implementation = { _: Unit -> throw NotFoundException("nope") }
        )

        val meta = path.path("meta") include MetaEndpoints(
            packageName = "com.lightningkite.lightningserver.typed",
            database = database,
            cache = cache,
        )
    }

    private fun bulk(body: String, block: () -> Unit = {}) = TestServer.test(settings = {}) {
        Observed.reset()
        runBlocking {
            serverRuntime.handle(
                HttpRequest<PathSpec>(
                    path = RawHttpEndpoint(asString = "/meta/bulk", method = HttpMethod.POST),
                    queryParameters = QueryParameters.EMPTY,
                    headers = HttpHeaders.EMPTY,
                    domain = "example.com",
                    protocol = "https",
                    sourceIp = "local",
                    requestId = "outer-request",
                    body = TypedData.text(body, MediaType.Application.Json),
                )
            )
        }
        block()
    }

    @Test
    fun `logical scope interceptor observes every sub-request`() = bulk(
        """{"a":{"path":"/alpha","method":"GET"},"b":{"path":"/beta","method":"GET"}}"""
    ) {
        val paths = Observed.logical.map { it.path }
        assertTrue("/alpha" in paths, "sub-request /alpha was not intercepted; saw $paths")
        assertTrue("/beta" in paths, "sub-request /beta was not intercepted; saw $paths")
    }

    @Test
    fun `connection scope interceptor observes only the physical request`() = bulk(
        """{"a":{"path":"/alpha","method":"GET"},"b":{"path":"/beta","method":"GET"}}"""
    ) {
        assertEquals(
            listOf("/meta/bulk"),
            Observed.connection.map { it.path },
            "connection-scoped interceptors must not re-run per sub-request",
        )
    }

    @Test
    fun `each sub-request carries its own id parented to the outer request`() = bulk(
        """{"a":{"path":"/alpha","method":"GET"},"b":{"path":"/beta","method":"GET"}}"""
    ) {
        val subs = Observed.logical.filter { it.path != "/meta/bulk" }
        assertEquals(2, subs.size, "expected two sub-requests, saw ${Observed.logical.map { it.path }}")
        subs.forEach { assertEquals("outer-request", it.parentRequestId) }
        assertEquals(2, subs.map { it.requestId }.toSet().size, "sub-requests must have distinct ids")
        assertTrue(subs.none { it.requestId == "outer-request" }, "a sub-request reused the outer id")
    }

    /**
     * The guard exists because a sub-request that kept the outer ID, or carried no parent at all,
     * would be either indistinguishable from the request that carried it or unattributable to it —
     * and both corrupt the audit trail silently rather than failing.
     */
    @Test
    fun `handleSubRequest rejects a request that was not derived as a sub-request`() =
        TestServer.test(settings = {}) {
            val notASubRequest = HttpRequest<PathSpec>(
                path = RawHttpEndpoint(asString = "/alpha", method = HttpMethod.GET),
                queryParameters = QueryParameters.EMPTY,
                headers = HttpHeaders.EMPTY,
                domain = "example.com",
                protocol = "https",
                sourceIp = "local",
                requestId = "hand-rolled",
            )
            val failure = assertFailsWith<IllegalArgumentException> {
                runBlocking { serverRuntime.handleSubRequest(notASubRequest) }
            }
            assertTrue(
                failure.message.orEmpty().contains("subRequest"),
                "the message should name the correct way to build one; was: ${failure.message}",
            )
        }

    @Test
    fun `a failing sub-request is still observed`() = bulk(
        """{"a":{"path":"/alpha","method":"GET"},"bad":{"path":"/boom","method":"GET"}}"""
    ) {
        val paths = Observed.logical.map { it.path }
        assertTrue("/boom" in paths, "a sub-request that threw was not intercepted; saw $paths")
    }
}
