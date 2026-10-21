@file:OptIn(com.lightningkite.lightningserver.EngineApi::class)

package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.ExecutionInterceptor
import com.lightningkite.lightningserver.runtime.Invocation
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.lightningserver.runtime.handleRoot
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.Unsafe
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.Database
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * `/meta/bulk` runs each part as its own execution. The HTTP chain sees only the physical request,
 * while the execution chain sees every part with its own request and its own id, parented to the
 * bulk request, so access logging and auditing can attribute each part independently.
 */
class BulkInterceptorScopeTest {

    private data class Seen(val path: String, val requestId: Execution.ID, val parentRequestId: Execution.ID?)

    @OptIn(InternalLightningServerApi::class, Unsafe::class) // SAFETY: Only compared for identity, never ordered by time
    private val outerRequestId = Execution.ID(UuidV7.fromRawUnchecked(Uuid.parse("00000000-0000-4000-8000-000000000001")))

    private object Observed {
        val logical: MutableList<Seen> = Collections.synchronizedList(mutableListOf())
        val physical: MutableList<Seen> = Collections.synchronizedList(mutableListOf())

        fun reset() {
            logical.clear()
            physical.clear()
        }
    }

    context(runtime: ServerRuntime)
    private fun record(into: MutableList<Seen>, request: HttpRequest<*>) {
        val execution = runtime.execution
        into.add(Seen("/" + request.path.pathSegments.toString(), execution.id, execution.parent))
    }

    private inner class LogicalRecorder : ExecutionInterceptor {
        override val name: String = "LogicalRecorder"

        context(runtime: ServerRuntime)
        override suspend fun <T> intercept(invocation: Invocation, cont: suspend context(ServerRuntime) () -> T): T {
            if (invocation is Invocation.Http) record(Observed.logical, invocation.request)
            return cont()
        }
    }

    private inner class PhysicalRecorder : HttpInterceptor {
        override val name: String = "PhysicalRecorder"

        context(runtime: ServerRuntime)
        override suspend fun <PATH : PathSpec> intercept(
            request: HttpRequest<PATH>,
            cont: suspend context(ServerRuntime) (HttpRequest<PATH>) -> HttpResponse,
        ): HttpResponse {
            record(Observed.physical, request)
            return cont(request)
        }
    }

    object TestServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
            install(BulkInterceptorScopeTest().LogicalRecorder())
            install(BulkInterceptorScopeTest().PhysicalRecorder())
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

        val callsAlpha = path.path("calls-alpha").get bind ApiHttpHandler(
            summary = "Calls alpha internally",
            auth = noAuth,
            implementation = { _: Unit -> alpha(request, Unit) }
        )

        val callsBoom = path.path("calls-boom").get bind ApiHttpHandler(
            summary = "Calls boom internally",
            auth = noAuth,
            implementation = { _: Unit ->
                try {
                    boom(request, Unit)
                } catch (e: HttpStatusException) {
                    "caught ${e.status.code} ${e.detail}"
                }
            }
        )

        val meta = path.path("meta") include MetaEndpoints(
            packageName = "com.lightningkite.lightningserver.typed",
            database = database,
            cache = cache,
        )
    }

    private fun bulk(body: String, block: () -> Unit = {}) = TestServer.test(settings = {}) {
        Observed.reset()
        runBlocking { root("/meta/bulk", HttpMethod.POST, TypedData.text(body, MediaType.Application.Json)) }
        block()
    }

    context(test: TestRunner<*>)
    private suspend fun root(path: String, method: HttpMethod, body: TypedData? = null): HttpResponse =
        engine.handleRoot(
            HttpRequest<PathSpec>(
                path = RawHttpEndpoint(asString = path, method = method),
                queryParameters = QueryParameters.EMPTY,
                headers = HttpHeaders.EMPTY,
                domain = "example.com",
                protocol = "https",
                sourceIp = "local",
                body = body,
            ),
            outerRequestId,
        )

    @Test
    fun `the execution interceptor chain observes every sub-request`() = bulk(
        """{"a":{"path":"/alpha","method":"GET"},"b":{"path":"/beta","method":"GET"}}"""
    ) {
        val paths = Observed.logical.map { it.path }
        assertTrue("/alpha" in paths, "sub-request /alpha was not intercepted; saw $paths")
        assertTrue("/beta" in paths, "sub-request /beta was not intercepted; saw $paths")
    }

    @Test
    fun `the http interceptor chain observes only the physical request`() = bulk(
        """{"a":{"path":"/alpha","method":"GET"},"b":{"path":"/beta","method":"GET"}}"""
    ) {
        assertEquals(
            listOf("/meta/bulk"),
            Observed.physical.map { it.path },
            "the http interceptor chain must not re-run per sub-request",
        )
    }

    @Test
    fun `each sub-request carries its own id parented to the outer request`() = bulk(
        """{"a":{"path":"/alpha","method":"GET"},"b":{"path":"/beta","method":"GET"}}"""
    ) {
        val subs = Observed.logical.filter { it.path != "/meta/bulk" }
        assertEquals(2, subs.size, "expected two sub-requests, saw ${Observed.logical.map { it.path }}")
        subs.forEach { assertEquals(outerRequestId, it.parentRequestId) }
        assertEquals(2, subs.map { it.requestId }.toSet().size, "sub-requests must have distinct ids")
        assertTrue(subs.none { it.requestId == outerRequestId }, "a sub-request reused the outer id")
    }

    @Test
    fun `a failing sub-request is still observed`() = bulk(
        """{"a":{"path":"/alpha","method":"GET"},"bad":{"path":"/boom","method":"GET"}}"""
    ) {
        val paths = Observed.logical.map { it.path }
        assertTrue("/boom" in paths, "a sub-request that threw was not intercepted; saw $paths")
    }

    @Test
    fun `an internal call returns the endpoint's output as its own execution`() = TestServer.test(settings = {}) {
        Observed.reset()
        val response = runBlocking { root("/calls-alpha", HttpMethod.GET) }
        assertEquals("\"alpha\"", runBlocking { response.body!!.text() })

        val inner = Observed.logical.single { it.path == "/alpha" }
        assertEquals(outerRequestId, inner.parentRequestId)
        assertEquals(listOf("/calls-alpha"), Observed.physical.map { it.path }, "an internal call ran the http chain")
    }

    @Test
    fun `an internal call throws the endpoint's exception`() = TestServer.test(settings = {}) {
        val response = runBlocking { root("/calls-boom", HttpMethod.GET) }
        assertEquals("\"caught 404 \"", runBlocking { response.body!!.text() })
    }
}
