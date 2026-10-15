package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.executePreDeployTasks
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.UnauditedDatabaseAccess
import com.lightningkite.lightningserver.typed.explicitModelInfo
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class DataAccessLogTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val origins = OriginRecording(database).also { it.install() }
        val registry = path.path("registry") include AuditRegistry(
            database.registerTable("AuditModelRegistration", AuditModelRegistration.serializer()),
            database.registerTable("AuditFieldRegistration", AuditFieldRegistration.serializer()),
        )
        val dataAccessLog = AuditQueryLog(origins, registry, database)

        val patients = database.registerTable("Patient", Patient.serializer())

        /** Never registered, so every query against it must fail without running. */
        val orders = database.registerTable("Order", Order.serializer())

        val patientInfo = database.explicitModelInfo(
            auth = noAuth,
            serializer = Patient.serializer(),
            idSerializer = Patient.ID.serializer(),
            tableName = "PatientInfo",
            log = { it.logQueries(dataAccessLog) },
            permissions = { ModelPermissions() },
        )
    }

    private fun onServer(block: suspend context(ServerRuntime) () -> Unit) = runBlocking {
        TestServer.test(settings = { database set Database.Settings() }) {
            executePreDeployTasks()
            execute {
                TestServer.registry.register(listOf(Patient.serializer().descriptor))
                block(serverRuntime)
            }
        }
    }

    context(server: ServerRuntime)
    private suspend fun logged() = TestServer.dataAccessLog.table().find(Condition.Always).toList()

    context(server: ServerRuntime)
    private fun auditedTable() = TestServer.patients().logQueries(TestServer.dataAccessLog)

    private fun patient() = Patient(_id = Patient.ID(Uuid.random()), name = "Ada", ssn = "s")

    // ===================== reads that disclose no record =====================

    @Test
    fun `a group count over a sensitive field is recorded`() = onServer {
        auditedTable().groupCount(Condition.Always, Patient.path.ssn)

        val row = logged().single()
        assertEquals(AuditQueryRecord.Operation.GroupCount, row.operation)
        assertEquals("ssn", row.groupBy)
    }

    @Test
    fun `a find that matches nothing is recorded with its condition`() = onServer {
        assertEquals(0, auditedTable().find(condition<Patient> { it.ssn.eq("guess") }).count())

        val row = logged().single()
        assertEquals(AuditQueryRecord.Operation.Find, row.operation)
        assertTrue("guess" in row.condition, "the probed value is not in the record: ${row.condition}")
    }

    @Test
    fun `a count is recorded`() = onServer {
        auditedTable().count(condition<Patient> { it.ssn.eq("probe") })

        assertEquals(AuditQueryRecord.Operation.Count, logged().single().operation)
    }

    @Test
    fun `a sort and its paging are recorded`() = onServer {
        val sort = listOf(SortPart(Patient.path.ssn))
        auditedTable().find(Condition.Always, orderBy = sort, skip = 0, limit = 1).toList()
        auditedTable().find(Condition.Always, orderBy = sort, skip = 4_000, limit = 1).toList()

        val rows = logged().sortedBy { it.skip }
        assertEquals(listOf(0, 4_000), rows.map { it.skip })
        assertEquals(listOf(1, 1), rows.map { it.limit })
        assertTrue(rows.all { it.sort?.contains("ssn") == true }, "the ordering was not recorded: ${rows.map { it.sort }}")
    }

    @Test
    fun `an unsorted find records no sort`() = onServer {
        auditedTable().find(Condition.Always).toList()

        assertEquals(null, logged().single().sort)
    }

    // ===================== writes =====================

    @Test
    fun `an insert records how many models it inserted`() = onServer {
        auditedTable().insert(listOf(patient(), patient()))

        val row = logged().single()
        assertEquals(AuditQueryRecord.Operation.Insert, row.operation)
        assertEquals(2, row.limit)
    }

    @Test
    fun `an update records its modification`() = onServer {
        val ada = patient()
        val table = auditedTable()
        table.insert(listOf(ada))
        table.updateOneById(ada._id, modification<Patient> { it.ssn assign "changed" })

        val update = logged().single { it.operation == AuditQueryRecord.Operation.Update }
        assertTrue(update.modification?.contains("changed") == true, "the modification was not recorded: ${update.modification}")
    }

    @Test
    fun `a delete is recorded`() = onServer {
        auditedTable().deleteManyIgnoringOld(Condition.Always)

        assertEquals(AuditQueryRecord.Operation.Delete, logged().single().operation)
    }

    // ===================== attribution and failure =====================

    @Test
    fun `the record names the origin and model it was made under`() = onServer {
        auditedTable().count(Condition.Always)

        val row = logged().single()
        assertEquals(serverRuntime.execution.origin.originId, row.origin)
        assertEquals(TestServer.registry.assignments.await().modelType(Patient.serializer().descriptor), row.modelType)
    }

    @Test
    fun `a query against an unregistered model fails without running`() = onServer {
        val order = Order(Order.ID(Uuid.random()), Payment.Card("4242"))

        assertFailsWith<IllegalStateException> {
            TestServer.orders().logQueries(TestServer.dataAccessLog).insert(listOf(order))
        }
        assertEquals(emptyList(), TestServer.orders().find(Condition.Always).toList(), "the insert ran without a record")
        assertEquals(emptyList(), logged())
    }

    @Test
    fun `a partial find records the fields it asked for`() = onServer {
        auditedTable().findPartial(setOf(Patient.path.ssn), Condition.Always).toList()

        val row = logged().single()
        assertEquals(AuditQueryRecord.Operation.Find, row.operation)
        assertTrue(row.fields?.contains("ssn") == true, "the requested fields were not recorded: ${row.fields}")
    }

    @Test
    fun `an aggregate records the aggregate and its field`() = onServer {
        auditedTable().aggregate(Aggregate.Average, Condition.Always, Patient.path.visits)

        val row = logged().single()
        assertEquals(AuditQueryRecord.Operation.Aggregate, row.operation)
        assertEquals("visits", row.groupBy)
        assertTrue(row.aggregate?.contains("Average") == true, "the aggregate was not recorded: ${row.aggregate}")
    }

    @Test
    fun `a group aggregate records its grouping, aggregate and field`() = onServer {
        auditedTable().groupAggregate(Aggregate.Sum, Condition.Always, Patient.path.ssn, Patient.path.visits)

        val row = logged().single()
        assertEquals(AuditQueryRecord.Operation.GroupAggregate, row.operation)
        assertEquals("ssn", row.groupBy)
        assertTrue(row.aggregate?.let { "Sum" in it && "visits" in it } == true, "the aggregate was not recorded: ${row.aggregate}")
    }

    @Test
    fun `a replace and an upsert are recorded`() = onServer {
        val ada = patient()
        val table = auditedTable()
        table.replaceOneById(ada._id, ada)
        table.upsertOneById(ada._id, ada)

        assertEquals(
            listOf(AuditQueryRecord.Operation.Replace, AuditQueryRecord.Operation.Update).sorted(),
            logged().map { it.operation }.sorted(),
        )
    }

    @Test
    fun `the ignoring variants are recorded`() = onServer {
        val ada = patient()
        val table = auditedTable()
        table.updateManyIgnoringResult(Condition.Always, modification { it.ssn assign "x" })
        table.updateOneIgnoringResult(Condition.Always, modification { it.ssn assign "y" })
        table.replaceOneIgnoringResult(Condition.Always, ada)
        table.upsertOneIgnoringResult(condition { it._id eq ada._id }, modification { it.ssn assign "z" }, ada)
        table.deleteOneIgnoringOld(Condition.Always)

        assertEquals(
            listOf(
                AuditQueryRecord.Operation.Update, AuditQueryRecord.Operation.Update, AuditQueryRecord.Operation.Replace,
                AuditQueryRecord.Operation.Update, AuditQueryRecord.Operation.Delete,
            ).sorted(),
            logged().map { it.operation }.sorted(),
        )
    }

    @Test
    fun `the origin a record names is written`() = onServer {
        auditedTable().count(Condition.Always)

        val origin = logged().single().origin
        assertTrue(TestServer.origins.table().find(Condition.Always).toList().any { it._id == origin }, "the record names an origin with no row")
    }

    // ===================== through ModelInfo =====================

    @Test
    fun `a ModelInfo table records each query exactly once`() = onServer {
        TestServer.patientInfo.table().count(Condition.Always)

        assertEquals(AuditQueryRecord.Operation.Count, logged().single().operation)
    }

    @Test
    fun `a ModelInfo base table is recorded`() = onServer {
        TestServer.patientInfo.baseTable().count(Condition.Always)

        assertEquals(AuditQueryRecord.Operation.Count, logged().single().operation)
    }

    @OptIn(UnauditedDatabaseAccess::class)
    @Test
    fun `a ModelInfo direct table is not recorded`() = onServer {
        TestServer.patientInfo.dangerouslyDirectTable().count(Condition.Always)

        assertEquals(emptyList(), logged())
    }
}
