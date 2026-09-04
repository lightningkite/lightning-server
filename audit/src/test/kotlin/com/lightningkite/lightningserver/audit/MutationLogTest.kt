package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.PreDeployTask
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.Initiator
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.forExecution
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

/**
 * The mutation log answers "who changed this record, and to what" — the question the data access
 * log's recorded `Modification` describes only as intent.
 *
 * Two things here are load-bearing beyond "a row appears". The `Ignoring*` methods must record just
 * as completely as their effect-returning twins, or the log is circumventable by picking a different
 * method on the same interface; and the value those upgraded calls hand back must be indistinguishable
 * from what the method they replaced would have returned, or wrapping a table silently changes what
 * every caller sees.
 */
@OptIn(InternalLightningServerApi::class)
class MutationLogTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())

        val audit = path.path("audit") include AuditCore(database)
        val mutationLog = path.path("audit-mutation") include MutationLog(audit)

        init {
            registerBasicMediaTypeCoders()
        }

        val patients = database.registerTable("Patient", Patient.serializer())

        /** An undecorated twin, so a decorated call's return value has something to be equal to. */
        val patientsUndecorated = database.registerTable("PatientUndecorated", Patient.serializer())

        /** Not audited, so it must generate no rows at all. */
        val plain = database.registerTable("Plain", PlainThing.serializer())

        /** Not audited itself, but contains an audited model — the shape that must be refused. */
        val wrappers = database.registerTable("Wrapper", PatientWrapper.serializer())

        /**
         * The registry assigns ids by scanning endpoint serializers, never tables, so an audited
         * model needs an endpoint that can return it before it has an id at all. Present only for
         * that — no test calls it.
         */
        val sample = Patient(_id = Uuid.parse("00000000-0000-0000-0000-0000000000b1"), name = "", ssn = "")

        val patientEndpoint = path.path("patient").get bind ApiHttpHandler(
            summary = "Patient",
            auth = noAuth,
            implementation = { _: Unit -> sample },
        )
    }

    /** The same server, with the escape hatch turned on. */
    object SummaryServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())

        val audit = path.path("audit") include AuditCore(database)
        val mutationLog = path.path("audit-mutation") include
            MutationLog(audit, BulkMutationDetail.SummaryOnly)

        init {
            registerBasicMediaTypeCoders()
        }

        val patients = database.registerTable("Patient", Patient.serializer())

        val sample = Patient(_id = Uuid.parse("00000000-0000-0000-0000-0000000000b2"), name = "", ssn = "")

        val patientEndpoint = path.path("patient").get bind ApiHttpHandler(
            summary = "Patient",
            auth = noAuth,
            implementation = { _: Unit -> sample },
        )
    }

    private suspend fun runPreDeployTasks(runtime: ServerRuntime) {
        val done = HashSet<PreDeployTask>()
        suspend fun run(task: PreDeployTask) {
            if (!done.add(task)) return
            task.dependencies().forEach { run(it) }
            with(runtime) { task.execute() }
        }
        runtime.server.preDeployTasks.values.forEach { run(it) }
    }

    /**
     * @param initiator What the execution should be attributed to. Defaults to the test runner's own
     *   `Initiator.Direct`, which is the case with no request record behind it.
     */
    private fun onServer(
        initiator: Initiator? = null,
        block: suspend context(ServerRuntime) (ServerRuntime) -> Unit,
    ) = runBlocking {
        TestServer.test(settings = { database set Database.Settings(); cache set Cache.Settings() }) {
            runPreDeployTasks(serverRuntime)
            val runtime = if (initiator == null) serverRuntime else serverRuntime.forExecution(initiator)
            block(runtime, runtime)
        }
    }

    private fun onSummaryServer(block: suspend context(ServerRuntime) (ServerRuntime) -> Unit) = runBlocking {
        SummaryServer.test(settings = { database set Database.Settings(); cache set Cache.Settings() }) {
            runPreDeployTasks(serverRuntime)
            block(serverRuntime, serverRuntime)
        }
    }

    context(server: ServerRuntime)
    private suspend fun logged() = TestServer.mutationLog.mutations().find(Condition.Always).toList()

    context(server: ServerRuntime)
    private fun auditedTable() = TestServer.mutationLog.mutationLogged(TestServer.patients())

    private fun patient(name: String, ssn: String = "s", id: Uuid = Uuid.random()) =
        Patient(_id = id, name = name, ssn = ssn)

    // ===================== one row per changed record, with both sides =====================

    @Test
    fun `insert records a row per inserted record`() = onServer {
        val table = auditedTable()
        val ada = patient("Ada")
        val grace = patient("Grace")
        table.insert(listOf(ada, grace))

        val rows = logged()
        assertEquals(2, rows.size, "one row per inserted record")
        assertEquals(setOf(ada._id.toString(), grace._id.toString()), rows.map { it.recordId }.toSet())
        assertTrue(rows.all { it.operation == MutationOperation.Insert })
        assertTrue(rows.all { it.old == null }, "an insert has no previous value")
        assertTrue(rows.any { it.new?.contains("Ada") == true }, "the inserted value was not recorded")
    }

    /**
     * The heart of the layer: the data access log records `ssn assign "changed"` as *intent*, and
     * cannot say what the row held before. Both sides live here.
     */
    @Test
    fun `updateOne records the value before and after`() = onServer {
        val table = auditedTable()
        val ada = patient("Ada", ssn = "before")
        table.insert(listOf(ada))
        table.updateOneById(ada._id, modification<Patient> { it.ssn assign "after" })

        val row = logged().single { it.operation == MutationOperation.Update }
        assertEquals(ada._id.toString(), row.recordId)
        assertTrue(row.old?.contains("before") == true, "the previous value was not recorded: ${row.old}")
        assertTrue(row.new?.contains("after") == true, "the resulting value was not recorded: ${row.new}")
    }

    @Test
    fun `replaceOne records the value before and after`() = onServer {
        val table = auditedTable()
        val ada = patient("Ada", ssn = "before")
        table.insert(listOf(ada))
        table.replaceOne(condition { it._id eq ada._id }, ada.copy(ssn = "after"))

        val row = logged().single { it.operation == MutationOperation.Replace }
        assertEquals(ada._id.toString(), row.recordId)
        assertTrue(row.old?.contains("before") == true, "the previous value was not recorded: ${row.old}")
        assertTrue(row.new?.contains("after") == true, "the resulting value was not recorded: ${row.new}")
    }

    @Test
    fun `upsertOne records the inserting branch with no previous value`() = onServer {
        val table = auditedTable()
        val ada = patient("Ada")
        table.upsertOne(condition { it._id eq ada._id }, modification { it.ssn assign "x" }, ada)

        val row = logged().single()
        assertEquals(MutationOperation.Upsert, row.operation)
        assertEquals(ada._id.toString(), row.recordId)
        assertNull(row.old, "nothing was there before, so there is no previous value to record")
        assertTrue(row.new?.contains("Ada") == true)
    }

    @Test
    fun `deleteOne records what was removed`() = onServer {
        val table = auditedTable()
        val ada = patient("Ada")
        table.insert(listOf(ada))
        table.deleteOne(condition { it._id eq ada._id })

        val row = logged().single { it.operation == MutationOperation.Delete }
        assertEquals(ada._id.toString(), row.recordId)
        assertTrue(row.old?.contains("Ada") == true, "the deleted value was not recorded: ${row.old}")
        assertNull(row.new, "a delete leaves no resulting value")
    }

    @Test
    fun `updateMany and deleteMany record a row per affected record`() = onServer {
        val table = auditedTable()
        val people = List(3) { patient("P$it") }
        table.insert(people)
        table.updateMany(Condition.Always, modification { it.ssn assign "bulk" })
        table.deleteMany(Condition.Always)

        val rows = logged()
        assertEquals(3, rows.count { it.operation == MutationOperation.Update })
        assertEquals(3, rows.count { it.operation == MutationOperation.Delete })
        assertEquals(
            people.map { it._id.toString() }.toSet(),
            rows.filter { it.operation == MutationOperation.Delete }.map { it.recordId }.toSet(),
        )
    }

    /**
     * A call that matched nothing changed nothing. Recording the *attempt* is the data access log's
     * job — it fails closed and writes the condition before the call runs — so a row here would be a
     * change that never happened, which is the one thing an audit table must not contain.
     */
    @Test
    fun `a mutation that matches nothing writes no rows`() = onServer {
        val table = auditedTable()
        table.updateMany(condition { it.name eq "nobody" }, modification { it.ssn assign "x" })
        table.deleteOne(condition { it.name eq "nobody" })

        assertEquals(emptyList(), logged())
    }

    // ===================== the Ignoring* variants record in full by default =====================

    @Test
    fun `replaceOneIgnoringResult records full detail by default`() = onServer {
        val table = auditedTable()
        val ada = patient("Ada", ssn = "before")
        table.insert(listOf(ada))
        table.replaceOneIgnoringResult(condition { it._id eq ada._id }, ada.copy(ssn = "after"))

        val row = logged().single { it.operation == MutationOperation.Replace }
        assertEquals(ada._id.toString(), row.recordId)
        assertTrue(row.old?.contains("before") == true, "the previous value was skipped: ${row.old}")
        assertTrue(row.new?.contains("after") == true, "the resulting value was skipped: ${row.new}")
        assertNull(row.affectedCount, "a per-row record needs no count")
    }

    @Test
    fun `upsertOneIgnoringResult records full detail by default`() = onServer {
        val table = auditedTable()
        val ada = patient("Ada", ssn = "before")
        table.insert(listOf(ada))
        table.upsertOneIgnoringResult(
            condition { it._id eq ada._id },
            modification { it.ssn assign "after" },
            ada,
        )

        val row = logged().single { it.operation == MutationOperation.Upsert }
        assertEquals(ada._id.toString(), row.recordId)
        assertTrue(row.old?.contains("before") == true, "the previous value was skipped: ${row.old}")
        assertTrue(row.new?.contains("after") == true, "the resulting value was skipped: ${row.new}")
    }

    @Test
    fun `updateOneIgnoringResult records full detail by default`() = onServer {
        val table = auditedTable()
        val ada = patient("Ada", ssn = "before")
        table.insert(listOf(ada))
        table.updateOneIgnoringResult(condition { it._id eq ada._id }, modification { it.ssn assign "after" })

        val row = logged().single { it.operation == MutationOperation.Update }
        assertEquals(ada._id.toString(), row.recordId)
        assertTrue(row.old?.contains("before") == true, "the previous value was skipped: ${row.old}")
        assertTrue(row.new?.contains("after") == true, "the resulting value was skipped: ${row.new}")
    }

    /** The most expensive upgrade, and the one that most obviously must not be skippable. */
    @Test
    fun `updateManyIgnoringResult records full detail by default`() = onServer {
        val table = auditedTable()
        val people = List(3) { patient("P$it", ssn = "before") }
        table.insert(people)
        table.updateManyIgnoringResult(Condition.Always, modification { it.ssn assign "after" })

        val rows = logged().filter { it.operation == MutationOperation.Update }
        assertEquals(3, rows.size, "the bulk call produced a summary rather than a row per change")
        assertEquals(people.map { it._id.toString() }.toSet(), rows.map { it.recordId }.toSet())
        assertTrue(rows.all { it.old?.contains("before") == true })
        assertTrue(rows.all { it.new?.contains("after") == true })
        assertTrue(rows.all { it.affectedCount == null })
    }

    @Test
    fun `deleteOneIgnoringOld records what was removed by default`() = onServer {
        val table = auditedTable()
        val ada = patient("Ada")
        table.insert(listOf(ada))
        table.deleteOneIgnoringOld(condition { it._id eq ada._id })

        val row = logged().single { it.operation == MutationOperation.Delete }
        assertEquals(ada._id.toString(), row.recordId)
        assertTrue(row.old?.contains("Ada") == true, "the deleted value was skipped: ${row.old}")
    }

    @Test
    fun `deleteManyIgnoringOld records what was removed by default`() = onServer {
        val table = auditedTable()
        val people = List(3) { patient("P$it") }
        table.insert(people)
        table.deleteManyIgnoringOld(Condition.Always)

        val rows = logged().filter { it.operation == MutationOperation.Delete }
        assertEquals(3, rows.size, "the bulk call produced a summary rather than a row per change")
        assertEquals(people.map { it._id.toString() }.toSet(), rows.map { it.recordId }.toSet())
        assertTrue(rows.all { it.old != null }, "the deleted values were skipped")
    }

    // ===================== return-value equivalence, one test per method =====================

    /**
     * Each upgraded call must return exactly what the method it replaced would have. These run the
     * same call against a decorated table and an undecorated twin holding the same rows, so the
     * expected value is the real implementation's rather than a restatement of its documentation.
     */
    private fun equivalence(
        seed: List<Patient>,
        call: suspend (Table<Patient>) -> Any?,
    ) = onServer {
        val decorated = auditedTable()
        val undecorated = TestServer.patientsUndecorated()
        decorated.insert(seed)
        undecorated.insert(seed)

        assertEquals(call(undecorated), call(decorated), "the decorator changed what the caller sees")
    }

    private val ada = patient("Ada", ssn = "before", id = Uuid.parse("00000000-0000-4000-8000-00000000aaa1"))

    @Test
    fun `replaceOneIgnoringResult returns what the undecorated table returns when it matches`() =
        equivalence(listOf(ada)) {
            it.replaceOneIgnoringResult(condition { p -> p._id eq ada._id }, ada.copy(ssn = "after"))
        }

    @Test
    fun `replaceOneIgnoringResult returns what the undecorated table returns when it misses`() =
        equivalence(listOf(ada)) {
            it.replaceOneIgnoringResult(condition { p -> p.name eq "nobody" }, ada.copy(ssn = "after"))
        }

    /**
     * The trap: this one reports whether an element *already existed*, not whether anything changed,
     * so the obvious `new != null` derivation would invert the answer on the inserting branch.
     */
    @Test
    fun `upsertOneIgnoringResult returns what the undecorated table returns when it inserts`() =
        equivalence(listOf()) {
            it.upsertOneIgnoringResult(
                condition { p -> p._id eq ada._id },
                modification { p -> p.ssn assign "after" },
                ada,
            )
        }

    @Test
    fun `upsertOneIgnoringResult returns what the undecorated table returns when it updates`() =
        equivalence(listOf(ada)) {
            it.upsertOneIgnoringResult(
                condition { p -> p._id eq ada._id },
                modification { p -> p.ssn assign "after" },
                ada,
            )
        }

    @Test
    fun `updateOneIgnoringResult returns what the undecorated table returns when it matches`() =
        equivalence(listOf(ada)) {
            it.updateOneIgnoringResult(condition { p -> p._id eq ada._id }, modification { p -> p.ssn assign "after" })
        }

    @Test
    fun `updateOneIgnoringResult returns what the undecorated table returns when it misses`() =
        equivalence(listOf(ada)) {
            it.updateOneIgnoringResult(condition { p -> p.name eq "nobody" }, modification { p -> p.ssn assign "after" })
        }

    @Test
    fun `updateManyIgnoringResult returns the number of affected rows`() =
        equivalence(List(3) { patient("P$it") }) {
            it.updateManyIgnoringResult(Condition.Always, modification { p -> p.ssn assign "after" })
        }

    @Test
    fun `updateManyIgnoringResult returns zero when nothing matches`() =
        equivalence(List(3) { patient("P$it") }) {
            it.updateManyIgnoringResult(condition { p -> p.name eq "nobody" }, modification { p -> p.ssn assign "after" })
        }

    @Test
    fun `deleteOneIgnoringOld returns what the undecorated table returns when it matches`() =
        equivalence(listOf(ada)) { it.deleteOneIgnoringOld(condition { p -> p._id eq ada._id }) }

    @Test
    fun `deleteOneIgnoringOld returns what the undecorated table returns when it misses`() =
        equivalence(listOf(ada)) { it.deleteOneIgnoringOld(condition { p -> p.name eq "nobody" }) }

    @Test
    fun `deleteManyIgnoringOld returns the number of deleted rows`() =
        equivalence(List(3) { patient("P$it") }) { it.deleteManyIgnoringOld(Condition.Always) }

    @Test
    fun `deleteManyIgnoringOld returns zero when nothing matches`() =
        equivalence(List(3) { patient("P$it") }) { it.deleteManyIgnoringOld(condition { p -> p.name eq "nobody" }) }

    // ===================== the escape hatch =====================

    /**
     * Counts the effect-returning calls, which is how "did the cheap path stay cheap" is observable
     * from outside: the upgrade is precisely a call to [Table.updateMany] instead.
     */
    private class CountingTable<T : Any>(override val wraps: Table<T>) : Table<T> by wraps {
        var updateManyCalls: Int = 0
        var deleteManyCalls: Int = 0

        override suspend fun updateMany(
            condition: Condition<T>,
            modification: Modification<T>,
        ): CollectionChanges<T> {
            updateManyCalls++
            return wraps.updateMany(condition, modification)
        }

        override suspend fun deleteMany(condition: Condition<T>): List<T> {
            deleteManyCalls++
            return wraps.deleteMany(condition)
        }
    }

    @Test
    fun `SummaryOnly writes one summary row and does not materialise the changed rows`() = onSummaryServer {
        val counting = CountingTable(SummaryServer.patients())
        val table = SummaryServer.mutationLog.mutationLogged(counting)
        table.insert(List(3) { patient("P$it") })
        val affected = table.updateManyIgnoringResult(Condition.Always, modification { it.ssn assign "after" })

        assertEquals(3, affected)
        assertEquals(0, counting.updateManyCalls, "SummaryOnly took the expensive path anyway")

        val rows = SummaryServer.mutationLog.mutations().find(Condition.Always).toList()
        val summary = rows.single { it.operation == MutationOperation.Update }
        assertEquals(3, summary.affectedCount)
        assertNull(summary.recordId, "a summary row names no single record")
        assertNull(summary.old)
        assertNull(summary.new)
    }

    @Test
    fun `SummaryOnly summarises deletes too`() = onSummaryServer {
        val counting = CountingTable(SummaryServer.patients())
        val table = SummaryServer.mutationLog.mutationLogged(counting)
        table.insert(List(2) { patient("P$it") })
        assertEquals(2, table.deleteManyIgnoringOld(Condition.Always))
        assertEquals(0, counting.deleteManyCalls, "SummaryOnly took the expensive path anyway")

        val summary = SummaryServer.mutationLog.mutations().find(Condition.Always).toList()
            .single { it.operation == MutationOperation.Delete }
        assertEquals(2, summary.affectedCount)
        assertNull(summary.recordId)
    }

    /** SummaryOnly is about the `Ignoring*` variants only; a call that already has the rows keeps them. */
    @Test
    fun `SummaryOnly still records effect-returning calls in full`() = onSummaryServer {
        val table = SummaryServer.mutationLog.mutationLogged(SummaryServer.patients())
        val people = List(2) { patient("P$it") }
        table.insert(people)
        table.updateMany(Condition.Always, modification { it.ssn assign "after" })

        val rows = SummaryServer.mutationLog.mutations().find(Condition.Always).toList()
            .filter { it.operation == MutationOperation.Update }
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.recordId != null && it.affectedCount == null })
    }

    // ===================== attribution =====================

    /** A task has no request record to point at, so an id there would join to nothing. */
    @Test
    fun `a schedule tick records no request id`() {
        val scheduleId = Uuid.parse("00000000-0000-4000-8000-000000000501")
        val schedule = Initiator.Schedule(
            executionId = scheduleId,
            attributedTo = scheduleId,
            location = PathSegments(listOf("schedule", "nightly")),
        )
        onServer(schedule) {
            auditedTable().insert(listOf(patient("Ada")))

            val row = logged().single()
            assertNull(row.requestId, "a schedule tick has no RequestRecord, so this would dangle")
            assertEquals(schedule.executionId, row.executionId)
            assertEquals("schedule", row.initiatorKind)
            assertTrue("schedule" in row.initiator && "nightly" in row.initiator, row.initiator)
        }
    }

    @Test
    fun `an http request records the id its request record is keyed by`() {
        val http = Initiator.Http(
            executionId = Uuid.parse("00000000-0000-4000-8000-000000000601"),
            endpoint = RawHttpEndpoint<PathSpec>(asString = "/patient", method = HttpMethod.GET),
        )
        onServer(http) {
            auditedTable().insert(listOf(patient("Ada")))

            val row = logged().single()
            assertEquals(http.executionId, row.requestId)
            assertEquals(http.executionId, row.executionId)
            assertEquals("http", row.initiatorKind)
            assertTrue("patient" in row.initiator, row.initiator)
        }
    }

    /**
     * The query the layer exists for: "everything that changed because of request X", in one indexed
     * lookup rather than a walk of parent pointers.
     */
    @Test
    fun `an indirect mutation chains back to the root execution`() {
        val root = Uuid.parse("00000000-0000-4000-8000-000000000701")
        val nested = Initiator.Task(
            executionId = Uuid.parse("00000000-0000-4000-8000-000000000702"),
            causedBy = root,
            rootExecutionId = root,
            attributedTo = root,
            location = PathSegments(listOf("task", "cleanup")),
        )
        onServer(nested) {
            auditedTable().insert(listOf(patient("Ada")))

            val row = logged().single()
            assertEquals(root, row.rootExecutionId)
            assertEquals(root, row.causedBy)
            assertEquals(nested.executionId, row.executionId)
            assertEquals("task", row.initiatorKind)
        }
    }

    // ===================== gating =====================

    /** The decorator is meant to be passed on every model; an unaudited one must cost nothing. */
    @Test
    fun `an unaudited model generates no rows`() = onServer {
        val table = TestServer.mutationLog.mutationLogged(TestServer.plain())
        table.insert(listOf(PlainThing(Uuid.random(), "x")))
        table.deleteMany(Condition.Always)

        assertEquals(emptyList(), logged())
    }

    /**
     * A table whose declared type is not itself audited but whose children are must be refused. A row
     * names one model, so this shape cannot be attributed — and quietly passing it through would
     * leave every change to the audited model inside unrecorded and silent.
     */
    @Test
    fun `a table that merely contains audited models is refused`() = onServer {
        try {
            TestServer.mutationLog.mutationLogged(TestServer.wrappers())
            fail("a table containing audited models was silently left unlogged")
        } catch (e: IllegalStateException) {
            assertTrue("Patient" in (e.message ?: ""), "the contained model was not named: ${e.message}")
        }
    }

    // ===================== failure behaviour =====================

    /**
     * The asymmetry with the layers above, and the one that has to be verified rather than asserted
     * in a comment: the change has already committed by the time there is anything to record, so
     * throwing would report failure for something that happened and invite a double-applying retry.
     */
    @Test
    fun `a mutation whose record cannot be written still happens`() = onServer { runtime ->
        val underlying = TestServer.patients()
        val table = MutationLogTable(
            wraps = underlying,
            modelId = { 1 },
            requestId = null,
            executionId = Uuid.random(),
            causedBy = null,
            rootExecutionId = Uuid.random(),
            attributedTo = Uuid.random(),
            initiatorKind = "direct",
            initiator = "{}",
            json = runtime.internalSerialization.json,
            nowMillis = { 1L },
            write = { throw RuntimeException("audit sink down") },
            bulkDetail = BulkMutationDetail.RecordEveryRow,
        )

        val ada = patient("Ada")
        table.insert(listOf(ada))
        assertEquals(
            listOf(ada),
            underlying.find(Condition.Always).toList(),
            "the insert was rolled back or refused because its record could not be written",
        )

        // And the upgraded path still hands back the right answer with the sink broken.
        assertTrue(table.deleteOneIgnoringOld(condition { it._id eq ada._id }))
        assertEquals(emptyList(), underlying.find(Condition.Always).toList())
    }

    // ===================== the row is readable =====================

    /** A row is only useful if the model id resolves back through the registry the core owns. */
    @Test
    fun `the recorded model id resolves through the registry`() = onServer {
        auditedTable().insert(listOf(patient("Ada")))

        val row = logged().single()
        // Keyed by the full serial name, which is what the walk records — not the class's short name.
        val serialName = Patient.serializer().descriptor.serialName
        assertEquals(TestServer.audit.registry.await().modelId(serialName), row.modelId)
    }
}
