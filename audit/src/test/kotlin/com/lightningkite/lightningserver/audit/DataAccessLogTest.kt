package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.definition.PreDeployTask
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The data access log exists to close what the disclosure log structurally cannot see: reads that
 * leak information without disclosing a record. Each test here is one of those channels.
 */
class DataAccessLogTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val audit = path.path("audit") include DisclosureAudit(database)

        init {
            registerBasicMediaTypeCoders()
        }

        val patients = database.registerTable("Patient", Patient.serializer())

        /** Not audited, so it must generate no rows at all. */
        val plain = database.registerTable("Plain", PlainThing.serializer())

        /**
         * The audit registry assigns ids by scanning endpoint serializers, never tables, so an
         * audited model needs an endpoint that can return it before it has an id at all. Present
         * only for that — no test calls it. See the limitation noted on [dataAccessLogged].
         */
        val sample = Patient(_id = Uuid.parse("00000000-0000-0000-0000-0000000000b1"), name = "", ssn = "")

        val patientEndpoint = path.path("patient").get bind ApiHttpHandler(
            summary = "Patient",
            auth = noAuth,
            implementation = { _: Unit -> sample },
        )
    }

    private fun onServer(block: suspend context(ServerRuntime) (ServerRuntime) -> Unit) = runBlocking {
        TestServer.test(settings = { database set Database.Settings(); cache set Cache.Settings() }) {
            val done = HashSet<PreDeployTask>()
            suspend fun run(task: PreDeployTask) {
                if (!done.add(task)) return
                task.dependencies().forEach { run(it) }
                with(serverRuntime) { task.execute() }
            }
            serverRuntime.server.preDeployTasks.values.forEach { run(it) }
            block(serverRuntime, serverRuntime)
        }
    }

    context(server: ServerRuntime)
    private suspend fun logged() = TestServer.audit.dataAccess().find(Condition.Always).toList()

    context(server: ServerRuntime)
    private fun auditedTable() = TestServer.audit.dataAccessLogged(TestServer.patients())

    @Test
    fun `a group count over a sensitive field is recorded, though it discloses no record`() = onServer {
        val table = auditedTable()
        table.groupCount(Condition.Always, Patient.path.ssn)

        val row = logged().single()
        assertEquals(DataAccessOperation.GroupCount, row.operation)
        assertEquals("ssn", row.groupBy)
    }

    /**
     * `find(ssn eq "X")` returning nothing discloses nothing under the field-presence rule, and tells
     * an attacker the same bit a count would. The condition is the evidence.
     */
    @Test
    fun `an existence probe that returns nothing is still recorded, with its condition`() = onServer {
        val table = auditedTable()
        assertEquals(0, table.find(condition<Patient> { it.ssn.eq("guess") }).count())

        val row = logged().single()
        assertEquals(DataAccessOperation.Find, row.operation)
        assertTrue("guess" in row.condition, "the probed value is not recoverable from the record")
    }

    /** A sort plus skip walks values without ever matching one, so the ordering has to be recorded. */
    @Test
    fun `a sort is recorded`() = onServer {
        val table = auditedTable()
        table.find(Condition.Always, orderBy = listOf(SortPart(Patient.path.ssn))).toList()

        val row = logged().single()
        assertTrue(row.sort?.contains("ssn") == true, "the ordering was not recorded: ${row.sort}")
    }

    @Test
    fun `a count is recorded`() = onServer {
        val table = auditedTable()
        table.count(condition<Patient> { it.ssn.eq("probe") })

        assertEquals(DataAccessOperation.Count, logged().single().operation)
    }

    /** Writes go through the same choke point; the modification is what says intent. */
    @Test
    fun `a write records its modification`() = onServer {
        val table = auditedTable()
        table.insert(listOf(Patient(_id = Uuid.random(), name = "Ada", ssn = "s")))

        val row = logged().single()
        assertEquals(DataAccessOperation.Insert, row.operation)
    }

    /** The decorator is meant to be passed on every model; an unaudited one must cost nothing. */
    @Test
    fun `an unaudited model generates no rows`() = onServer {
        val table = TestServer.audit.dataAccessLogged(TestServer.plain())
        table.insert(listOf(PlainThing(Uuid.random(), "x")))
        table.find(Condition.Always).toList()
        table.count(Condition.Always)

        assertEquals(emptyList(), logged())
    }

    /** Both ids are carried: one to join, one to place the query at a precise execution. */
    @Test
    fun `each row carries the joining request id and the precise execution id`() = onServer { runtime ->
        val table = auditedTable()
        table.count(Condition.Always)

        val row = logged().single()
        assertEquals(runtime.initiator.executionId, row.executionId)
        assertEquals(runtime.initiator.requestRecordId, row.requestId)
    }
}
