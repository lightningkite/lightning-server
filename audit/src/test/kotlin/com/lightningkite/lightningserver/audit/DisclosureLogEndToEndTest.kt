@file:OptIn(com.lightningkite.lightningserver.EngineApi::class)

package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.lightningserver.runtime.handleRoot
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.executePreDeployTasks
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.serialization.encoder
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.MetaEndpoints
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.Unsafe
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The whole disclosure log driven through a real server: install it, make a request, and read back
 * what an auditor would read.
 */
class DisclosureLogEndToEndTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        object Audit : AuditingBuilder.WithStandardRequestInfo(database) {
            val disclosures = install(AuditDisclosureLog())
        }
        val audit = path.path("audit") include Audit

        init {
            registerBasicMediaTypeCoders()
        }

        val ada = Patient(
            _id = Patient.ID(Uuid.parse("00000000-0000-0000-0000-0000000000a1")),
            name = "Ada",
            ssn = "secret",
        )

        val patient = path.path("patient").get bind ApiHttpHandler(
            summary = "Patient",
            auth = noAuth,
            implementation = { _: Unit -> ada },
        )

        /** Pushes [ada] through the server's encoder whenever the client sends anything. */
        val patientSocket = path.path("patient-socket") bind WebSocketHandler<PathSpec0, Unit>(
            willConnect = { },
            messageFromClient = {
                send(MediaType.Application.Json.encoder!!.ws(MediaType.Application.Json, Patient.serializer(), ada))
            },
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

    /** A fixed, readable request id, so a record can be asserted against the request that wrote it. */
    // SAFETY: Fixed, valid v7 ids.
    @OptIn(InternalLightningServerApi::class, Unsafe::class)
    private fun testId(n: Int) =
        Execution.ID(UuidV7.fromRawUnchecked(Uuid.parse("00000000-0000-7000-8000-" + n.toString().padStart(12, '0'))))

    private fun request(path: String, method: HttpMethod = HttpMethod.GET, body: String? = null, sourceIp: String = "10.0.0.1") =
        HttpRequest<PathSpec>(
            path = RawHttpEndpoint(asString = path, method = method),
            queryParameters = QueryParameters.EMPTY,
            headers = HttpHeaders.EMPTY,
            domain = "example.com",
            protocol = "https",
            sourceIp = sourceIp,
            body = body?.let { TypedData.text(it, MediaType.Application.Json) },
        )

    // Pre-deploy is what assigns the audit bit indices, so a disclosure cannot be read without it.
    private fun onServer(block: suspend context(ServerRuntime) Reader.() -> Unit) = runBlocking {
        TestServer.test(settings = { database set Database.Settings(); cache set Cache.Settings() }) {
            executePreDeployTasks()
            execute { block(serverRuntime, Reader()) }
        }
    }

    private class Reader {
        context(server: ServerRuntime)
        suspend fun requests() = TestServer.audit.origins.table().find(Condition.Always).toList()

        context(server: ServerRuntime)
        suspend fun disclosures() = TestServer.audit.disclosures.table().find(Condition.Always).toList()

        context(server: ServerRuntime)
        suspend fun pathsOf(record: AuditDisclosureRecord): Set<String> {
            val registry = TestServer.audit.registry.assignments.await()
            val byBit = registry.fields(record.recordType).entries.associate { it.value to it.key }
            return record.disclosed.map { byBit.getValue(it) }.toSet()
        }
    }

    /**
     * A disclosure row's instant comes from its own version-7 id, so it must reflect the engine's
     * selected clock. Pinned end-to-end because the minting happens in the interceptor: a row minted
     * with a v4 id would still serialize, still join, and still read back — it would just silently
     * answer "epoch" when asked when the disclosure happened.
     */
    @Test
    fun `a disclosure row carries the mint time of the clock that wrote it`() = runBlocking {
        val fixed = Instant.fromEpochMilliseconds(1_700_000_123_456)
        TestServer.test(
            settings = { database set Database.Settings(); cache set Cache.Settings() },
            clock = { object : Clock { override fun now(): Instant = fixed } },
        ) {
            executePreDeployTasks()
            val response = engine.handleRoot(request("/patient"), testId(20))
            assertEquals(HttpStatus.OK, response.status)
            execute { assertEquals(fixed, Reader().run { disclosures() }.single().at) }
        }
        Unit
    }

    @Test
    fun `a disclosed record is recorded against the request that disclosed it`() = onServer {
        val response = engine.handleRoot(request("/patient"), testId(1))
        assertEquals(HttpStatus.OK, response.status)

        val disclosure = disclosures().single()
        assertEquals(testId(1).originId, disclosure.origin)
        assertEquals(TestServer.ada._id.asUuid, disclosure.recordId)
        assertEquals(setOf("name", "ssn"), pathsOf(disclosure))

        val record = requests().single { it._id == testId(1).originId }
        assertEquals("10.0.0.1", record.request?.sourceIp)
        assertEquals("GET", record.location.substringBefore(' '))
        // TODO: OriginRecord no longer records an outcome or duration; restore once it does, or drop.
        // assertEquals("200", record.outcome)
        // assertNotNull(record.durationMs)
        assertNull(record.parent)
    }

    /** Origins are recorded lazily, so a request that references nothing leaves nothing behind. */
    @Test
    fun `a request that discloses nothing writes no records`() = onServer {
        engine.handleRoot(request("/plain"), testId(2))

        assertEquals(emptyList(), disclosures())
        assertEquals(emptyList(), requests())
    }

    /**
     * The request record must exist before anything points at it, which is why it is written at the
     * start rather than assembled at the end.
     */
    @Test
    fun `every disclosure refers to a request record that exists`() = onServer {
        engine.handleRoot(request("/patient"), testId(3))

        val known = requests().map { it._id }.toSet()
        disclosures().forEach {
            assertTrue(it.origin in known, "disclosure ${it._id} refers to unknown request ${it.origin}")
        }
    }

    /** Only the sub-request that disclosed references an origin, so it and the carrier it descends from are recorded. */
    @Test
    fun `a disclosing sub-request of a multiplexed request is recorded, parented to the carrier`() = onServer {
        engine.handleRoot(
            request(
                "/meta/bulk",
                HttpMethod.POST,
                body = """{"a":{"path":"/patient","method":"GET"},"b":{"path":"/plain","method":"GET"}}""",
            ),
            testId(4),
        )

        val subs = requests().filter { it.parent == testId(4).toExternal() }
        assertEquals(1, subs.size, "expected a record for the disclosing sub-request only; saw ${requests().map { it._id }}")
        assertTrue(requests().any { it._id == testId(4).originId }, "the carrying request was not recorded")

        val disclosure = disclosures().single()
        assertTrue(
            disclosure.origin in subs.map { it._id },
            "the disclosure was attributed to the outer request rather than the sub-request that made it",
        )
    }

    /**
     * A repeated trusted request id means a misconfigured proxy is about to merge two requests'
     * activity under one identifier. That has to be loud.
     */
    @Test
    fun `a duplicate request id from a different request fails the request and discloses nothing`() = onServer {
        val first = engine.handleRoot(request("/patient"), testId(5))
        assertEquals(HttpStatus.OK, first.status)

        val second = engine.handleRoot(request("/patient", sourceIp = "10.0.0.2"), testId(5))

        assertEquals(
            HttpStatus.InternalServerError,
            second.status,
            "a repeated request id must not quietly merge two requests' activity",
        )
        assertEquals(1, disclosures().size, "the rejected request disclosed a record anyway")
    }

    /** An identical row is what a child execution re-writing its parent's origin produces, so it is accepted. */
    @Test
    fun `a duplicate request id with an identical origin is accepted`() = onServer {
        assertEquals(HttpStatus.OK, engine.handleRoot(request("/patient"), testId(6)).status)
        assertEquals(HttpStatus.OK, engine.handleRoot(request("/patient"), testId(6)).status)

        assertEquals(1, requests().count { it._id == testId(6).originId })
    }

    @Test
    fun `a model pushed over a socket is recorded against the socket`() = runBlocking {
        TestServer.test(settings = { database set Database.Settings(); cache set Cache.Settings() }) {
            executePreDeployTasks()
            val socket = TestServer.patientSocket.test()
            socket.send(WebSocketFrame.Text("go"))

            execute {
                val disclosure = Reader().run { disclosures() }.single()
                assertEquals(socket.request.socketId.originId, disclosure.origin)
                assertEquals(TestServer.ada._id.asUuid, disclosure.recordId)
            }
        }
    }
}
