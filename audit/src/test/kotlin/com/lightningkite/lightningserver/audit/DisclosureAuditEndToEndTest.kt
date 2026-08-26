package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.definition.PreDeployTask
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.MetaEndpoints
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The whole disclosure log driven through a real server: install it, make a request, and read back
 * what an auditor would read.
 */
class DisclosureAuditEndToEndTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val audit = path.path("audit") include DisclosureAudit(database)

        init {
            registerBasicMediaTypeCoders()
        }

        val ada = Patient(
            _id = Uuid.parse("00000000-0000-0000-0000-0000000000a1"),
            name = "Ada",
            ssn = "secret",
        )

        val patient = path.path("patient").get bind ApiHttpHandler(
            summary = "Patient",
            auth = noAuth,
            implementation = { _: Unit -> ada },
        )

        val plain = path.path("plain").get bind ApiHttpHandler(
            summary = "Plain",
            auth = noAuth,
            implementation = { _: Unit -> "nothing audited here" },
        )

        val meta = path.path("meta") include MetaEndpoints(
            packageName = "com.lightningkite.lightningserver.audit",
            database = database,
            cache = cache,
        )
    }

    private fun request(path: String, method: HttpMethod = HttpMethod.GET, id: String, body: String? = null) =
        HttpRequest<PathSpec>(
            path = RawHttpEndpoint(asString = path, method = method),
            queryParameters = QueryParameters.EMPTY,
            headers = HttpHeaders.EMPTY,
            domain = "example.com",
            protocol = "https",
            sourceIp = "10.0.0.1",
            requestId = id,
            body = body?.let { TypedData.text(it, MediaType.Application.Json) },
        )

    private fun onServer(block: suspend context(ServerRuntime) Reader.() -> Unit) = runBlocking {
        TestServer.test(settings = { database set Database.Settings(); cache set Cache.Settings() }) {
            runPreDeployTasks(serverRuntime)
            block(serverRuntime, Reader())
        }
    }

    /**
     * Runs the server's pre-deploy tasks in dependency order, which is what assigns the audit bit
     * indices. Done here rather than through the runner so the ordering the real deploy pipeline
     * guarantees is reproduced explicitly.
     */
    private suspend fun runPreDeployTasks(runtime: ServerRuntime) {
        val done = HashSet<PreDeployTask>()
        suspend fun run(task: PreDeployTask) {
            if (!done.add(task)) return
            task.dependencies().forEach { run(it) }
            with(runtime) { task.execute() }
        }
        runtime.server.preDeployTasks.values.forEach { run(it) }
    }

    private class Reader {
        context(server: ServerRuntime)
        suspend fun requests() = TestServer.audit.requests().find(Condition.Always).toList()

        context(server: ServerRuntime)
        suspend fun disclosures() = TestServer.audit.disclosures().find(Condition.Always).toList()

        context(server: ServerRuntime)
        suspend fun pathsOf(record: DisclosureRecord): Set<String> {
            val registry = TestServer.audit.registry.await()
            val byBit = registry.fields(record.modelId).entries.associate { it.value to it.key }
            return record.fields.indices().map { byBit.getValue(it) }.toSet()
        }
    }

    @Test
    fun `a disclosed record is recorded against the request that disclosed it`() = onServer {
        val response = serverRuntime.handle(request("/patient", id = "req-1"))
        assertEquals(HttpStatus.OK, response.status)

        val disclosure = disclosures().single()
        assertEquals("req-1", disclosure.requestId)
        assertEquals(TestServer.ada._id, disclosure.recordId)
        assertEquals(setOf("name", "ssn"), pathsOf(disclosure))

        val record = requests().single { it._id == "req-1" }
        assertEquals("10.0.0.1", record.sourceIp)
        assertEquals("GET", record.method)
        assertEquals("200", record.outcome)
        assertNotNull(record.durationMs)
        assertNull(record.parentRequestId)
    }

    @Test
    fun `a request that discloses nothing still gets a request record and no disclosures`() = onServer {
        serverRuntime.handle(request("/plain", id = "req-2"))

        assertEquals(emptyList(), disclosures())
        assertEquals("200", requests().single { it._id == "req-2" }.outcome)
    }

    /**
     * The request record must exist before anything points at it, which is why it is written at the
     * start rather than assembled at the end.
     */
    @Test
    fun `every disclosure refers to a request record that exists`() = onServer {
        serverRuntime.handle(request("/patient", id = "req-3"))

        val known = requests().map { it._id }.toSet()
        disclosures().forEach {
            assertTrue(it.requestId in known, "disclosure ${it._id} refers to unknown request ${it.requestId}")
        }
    }

    @Test
    fun `each sub-request of a multiplexed request is recorded separately and parented`() = onServer {
        serverRuntime.handle(
            request(
                "/meta/bulk",
                HttpMethod.POST,
                id = "outer",
                body = """{"a":{"path":"/patient","method":"GET"},"b":{"path":"/plain","method":"GET"}}""",
            )
        )

        val subs = requests().filter { it.parentRequestId == "outer" }
        assertEquals(2, subs.size, "expected one request record per sub-request; saw ${requests().map { it._id }}")
        assertTrue(requests().any { it._id == "outer" }, "the carrying request was not recorded")

        val disclosure = disclosures().single()
        assertTrue(
            disclosure.requestId in subs.map { it._id },
            "the disclosure was attributed to the outer request rather than the sub-request that made it",
        )
    }

    /**
     * A repeated trusted request id means a misconfigured proxy is about to merge two principals'
     * activity under one identifier. That has to be loud.
     */
    @Test
    fun `a duplicate request id fails the request and discloses nothing`() = onServer {
        val first = serverRuntime.handle(request("/patient", id = "same"))
        assertEquals(HttpStatus.OK, first.status)

        val second = serverRuntime.handle(request("/patient", id = "same"))

        assertEquals(
            HttpStatus.InternalServerError,
            second.status,
            "a repeated request id must not quietly merge two requests' activity",
        )
        assertEquals(1, disclosures().size, "the rejected request disclosed a record anyway")
    }
}
