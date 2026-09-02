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
import com.lightningkite.lightningserver.typed.explicitModelInfo
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.database.ModelPermissions
import kotlinx.serialization.builtins.serializer
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
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

        /** Not audited itself, but contains an audited model — the shape that used to slip through. */
        val wrappers = database.registerTable("Wrapper", PatientWrapper.serializer())

        /** A full ModelInfo, so the two raw-table accessors can be told apart. */
        val patientInfo = database.explicitModelInfo(
            auth = noAuth,
            serializer = Patient.serializer(),
            idSerializer = Uuid.serializer(),
            tableName = "PatientInfo",
            log = { audit.dataAccessLogged(it) },
            permissions = { ModelPermissions() },
        )

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
    fun `an update records its modification`() = onServer {
        val table = auditedTable()
        val id = Uuid.random()
        table.insert(listOf(Patient(_id = id, name = "Ada", ssn = "s")))
        table.updateOneById(id, modification<Patient> { it.ssn assign "changed" })

        val update = logged().single { it.operation == DataAccessOperation.Update }
        assertTrue(
            update.modification?.contains("changed") == true,
            "the modification was not recoverable from the record: ${update.modification}",
        )
    }

    /**
     * The central guarantee, and until now untested: a query whose record cannot be written must not
     * run. Everything else here checks that rows appear; this checks that the read does not happen
     * when they cannot.
     */
    @Test
    fun `a query whose record cannot be written does not run`() = onServer { runtime ->
        var reads = 0
        val counting = object : com.lightningkite.services.database.Table<Patient> by TestServer.patients() {
            override suspend fun count(condition: com.lightningkite.services.database.Condition<Patient>): Int {
                reads++
                return 0
            }
        }
        val table = DataAccessLogTable(
            wraps = counting,
            modelId = { 1 },
            requestId = Uuid.random(),
            executionId = Uuid.random(),
            json = runtime.internalSerialization.json,
            nowMillis = { 1L },
            write = { throw RuntimeException("audit sink down") },
        )

        try {
            table.count(Condition.Always)
            fail("the read happened even though its record could not be written")
        } catch (_: RuntimeException) {
        }
        assertEquals(0, reads, "the underlying table was read despite the audit write failing")
    }

    /**
     * An ordering plus a moving offset walks values one at a time. Without skip and limit on the
     * record, the first probe and the four-thousandth are byte-identical, and the enumeration this
     * layer exists to expose stays invisible.
     */
    @Test
    fun `two probes at different offsets are distinguishable`() = onServer {
        val table = auditedTable()
        val sort = listOf(SortPart(Patient.path.ssn))
        table.find(Condition.Always, orderBy = sort, skip = 0, limit = 1).toList()
        table.find(Condition.Always, orderBy = sort, skip = 4_000, limit = 1).toList()

        val offsets = logged().sortedBy { it.skip }.map { it.skip }
        assertEquals(listOf(0, 4_000), offsets, "the walk's offsets were not recorded")
    }

    /**
     * A table whose declared type is not itself audited but whose children are must be refused, not
     * quietly passed through: a sealed parent's descriptor carries no annotation, so gating on that
     * alone would leave every read of its audited children unrecorded and silent.
     */
    @Test
    fun `a table that merely contains audited models is refused`() = onServer {
        try {
            TestServer.audit.dataAccessLogged(TestServer.wrappers())
            fail("a table containing audited models was silently left unlogged")
        } catch (e: IllegalStateException) {
            assertTrue("Patient" in (e.message ?: ""), "the contained model was not named: ${e.message}")
        }
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

    /**
     * `baseTable()` means "without permissions", not "without a record". It was the real bypass — one
     * production call site was updating a model through it with nothing logged — so it now goes
     * through the same decorator as `table()`.
     */
    @Test
    fun `baseTable is audited`() = onServer {
        TestServer.patientInfo.baseTable().count(Condition.Always)

        assertEquals(DataAccessOperation.Count, logged().single().operation)
    }

    /**
     * The genuine bypass still exists, because migrations and similar work legitimately need it. What
     * changed is that it is named, and reaching it requires opting in, so every bypass in a codebase
     * is greppable.
     */
    @OptIn(com.lightningkite.lightningserver.typed.UnauditedDatabaseAccess::class)
    @Test
    fun `dangerouslyDirectTable is not audited`() = onServer {
        TestServer.patientInfo.dangerouslyDirectTable().count(Condition.Always)

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
