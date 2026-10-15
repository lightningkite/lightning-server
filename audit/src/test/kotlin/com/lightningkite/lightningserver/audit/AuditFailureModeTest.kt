package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.EngineApi
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.lightningserver.runtime.handleRoot
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.executePreDeployTasks
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.sessions.AuthEvent
import com.lightningkite.lightningserver.sessions.install
import com.lightningkite.lightningserver.sessions.reportAuthEvent
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.Table
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/** What each log does when its own table will not accept a write. */
class AuditFailureModeTest {

    private class SinkDown : Exception("audit sink down")

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())

        val insertAttempts = AtomicInteger()

        /** Returns [table] with every insert failing. */
        private fun <T : Any> failingWrites(table: Runtime<Table<T>>) = object : Runtime<Table<T>> {
            context(server: Engine)
            override fun invoke(): Table<T> = object : Table<T> by table() {
                override suspend fun insert(models: Iterable<T>): List<T> {
                    insertAttempts.incrementAndGet()
                    throw SinkDown()
                }
            }
        }

        val origins = OriginRecording(
            database.registerTable("OriginRecord", OriginRecord.serializer(OriginRecord.StandardRequestInfo.serializer())),
            OriginRecording.RequestInfo.Standard
        ).also { it.install() }
        val registry = path.path("registry") include AuditRegistry(
            database.registerTable("AuditModelRegistration", AuditModelRegistration.serializer()),
            database.registerTable("AuditFieldRegistration", AuditFieldRegistration.serializer()),
        )
        val disclosures = install(
            AuditDisclosureLog(origins, registry, failingWrites(database.registerTable("Disclosures", AuditDisclosureRecord.serializer())))
        )
        val authEvents = install(
            AuthEventLog(origins, failingWrites(database.registerTable("AuthEvents", AuthEventRecord.serializer())))
        )
        val mutations = AuditMutationLog(
            origins, registry, failingWrites(database.registerTable("Mutations", AuditMutationRecord.serializer()))
        )
        val queries = AuditQueryLog(
            origins, registry, failingWrites(database.registerTable("Queries", AuditQueryRecord.serializer()))
        )

        val patients = database.registerTable("Patient", Patient.serializer())

        init {
            registerBasicMediaTypeCoders()
        }

        val ada = Patient(_id = Patient.ID(Uuid.random()), name = "Ada", ssn = "secret")

        val patient = path.path("patient").get bind ApiHttpHandler(
            summary = "Patient",
            auth = noAuth,
            implementation = { _: Unit -> ada },
        )
    }

    @OptIn(EngineApi::class)
    @Test
    fun `a disclosure that cannot be recorded is not sent`() = runBlocking {
        TestServer.test(settings = { database set Database.Settings() }) {
        executePreDeployTasks()
        val request = HttpRequest<PathSpec>(
            path = RawHttpEndpoint(asString = "/patient", method = HttpMethod.GET),
            queryParameters = QueryParameters.EMPTY,
            headers = HttpHeaders.EMPTY,
            domain = "example.com",
            protocol = "https",
            sourceIp = "10.0.0.1",
        )

        val response = engine.handleRoot(request, Execution.ID.generate())

        assertTrue(response.status != HttpStatus.OK, "the response was sent although its disclosure was not recorded")
        assertFalse(response.body?.text()?.contains("secret") == true, "the undisclosed value reached the client")
        }
    }

    @Test
    fun `a query that cannot be recorded does not run`() = runBlocking {
        TestServer.test(settings = { database set Database.Settings() }) {
        executePreDeployTasks()
        execute {
            val table = TestServer.patients().logQueries(TestServer.queries)

            assertFails { table.insert(listOf(TestServer.ada)) }
            assertEquals(emptyList(), TestServer.patients().find(Condition.Always).toList())
        }
        }
    }

    @Test
    fun `a change that cannot be recorded stands, but the caller sees the failure`() = runBlocking {
        TestServer.test(settings = { database set Database.Settings() }) {
        executePreDeployTasks()
        execute {
            val table = TestServer.patients().logAuditedFieldChanges(TestServer.mutations)

            assertFails { table.insert(listOf(TestServer.ada)) }
            assertEquals(listOf(TestServer.ada), TestServer.patients().find(Condition.Always).toList())
        }
        }
    }

    @Test
    fun `an auth event that cannot be recorded does not fail the caller`() = runBlocking {
        TestServer.test(settings = { database set Database.Settings() }) {
        executePreDeployTasks()
        execute {
            val before = TestServer.insertAttempts.get()
            serverRuntime.reportAuthEvent(
                AuthEvent.SessionRejected(AuthEvent.SessionRejected.Reason.Expired, "user-1", Uuid.random())
            )
            assertTrue(TestServer.insertAttempts.get() > before, "the event was never written, so its failure was not exercised")
        }
        }
    }
}
