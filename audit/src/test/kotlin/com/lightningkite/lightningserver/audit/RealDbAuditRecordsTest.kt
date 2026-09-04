package com.lightningkite.lightningserver.audit

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.database.*
import com.lightningkite.services.database.postgres.PostgresDatabase
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assume
import org.junit.Before
import java.sql.ResultSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The audit records against a **real** Postgres rather than the in-memory database every other test
 * in this module uses.
 *
 * These layers are written to on a fail-closed path, once per query and once per disclosed record,
 * so the things that only a real backend can answer — does a serialized `Condition` survive a text
 * column intact, do the declared indexes actually get created, is a duplicate id a hard error, does
 * volume hold up — are the ones that matter most. An in-memory map answers all four "yes" by
 * construction.
 *
 * Postgres is used because it is the backend whose column typing and index DDL can disagree with the
 * model; MongoDB stores the same records as documents and has far less room to differ.
 *
 * ## Skipping
 * The cluster is an embedded Postgres (Zonky), so no Docker daemon and no running server are needed.
 * It is still possible for it to fail to start — no binaries for the host architecture, a sandbox
 * that forbids spawning it — and in that case every test here is *skipped* via [Assume], not failed.
 * The skip reason names the underlying exception so a silent skip cannot be mistaken for a pass.
 */
class RealDbAuditRecordsTest {

    @Before
    fun skipWithoutARealDatabase() {
        Assume.assumeTrue(RealPostgres.unavailableReason.orEmpty(), RealPostgres.unavailableReason == null)
    }

    /**
     * Every test that needs disclosures shares this one table, and scopes its rows by `modelId`.
     *
     * Originally a workaround: the index was named `byRecord`, an explicit name is passed to the
     * backend verbatim, and Postgres relation names are per-schema rather than per-table — so
     * preparing a second `DisclosureRecord` table failed with `relation "byrecord" already exists`.
     * The name is now table-qualified, so this is merely tidy rather than necessary. Sharing one
     * table is still what these tests want.
     */
    private val DISCLOSURES = "RealDbDisclosure"

    // ---------------------------------------------------------------- 1. condition round-trips

    /**
     * `DataAccessRecord.condition` holds a `Condition<T>` serialized with the model's own serializer,
     * which means arbitrary user-supplied text — quotes, braces, backslashes, non-ASCII — inside a
     * single column. If the backend mangles any of it the recorded query is no longer the query that
     * ran, which is the one thing this table exists to state.
     */
    @Test
    fun `a serialized condition round-trips byte-identical`() = runBlocking {
        val table = table("RealDbCondition", DataAccessRecord.serializer())

        // Deliberately nasty: an embedded double quote, a brace, a backslash, and non-BMP unicode.
        val probed = """Ann "The Brace" O'Neil {x} \ 🩺 日本語"""
        val original = condition<Patient> { it.ssn.eq(probed) }
        val encoded = Json.encodeToString(Condition.serializer(Patient.serializer()), original)

        val record = dataAccess(condition = encoded)
        table.insert(listOf(record))

        val read = assertNotNull(table.get(record._id))
        assertEquals(encoded, read.condition, "the recorded condition did not survive the round trip")
        assertEquals(
            original,
            Json.decodeFromString(Condition.serializer(Patient.serializer()), read.condition),
            "the recorded condition no longer decodes to the condition that ran",
        )
        // Not `probed in read.condition`: JSON escapes the embedded quote, so the raw value is not a
        // substring of its own encoding. The non-ASCII is not escaped, and is what a text column
        // mis-declared as LATIN1 would mangle, so it is asserted literally.
        assertTrue("🩺 日本語" in read.condition, "non-ASCII did not survive the column")
    }

    /**
     * The same guarantee for the other free-text columns, which carry the sort, the modification and
     * the aggregate. They are populated from the same JSON encoders and are nullable, so a backend
     * that coerced an absent value to `""` would quietly turn "no ordering" into "some ordering".
     */
    @Test
    fun `the optional query columns keep null and text apart`() = runBlocking {
        val table = table("RealDbOptionalColumns", DataAccessRecord.serializer())

        val withText = dataAccess(condition = "{}", modification = """{"SetField":{"ssn":"\"quoted\""}}""")
        val withoutText = dataAccess(condition = "{}")
        table.insert(listOf(withText, withoutText))

        assertEquals(withText.modification, assertNotNull(table.get(withText._id)).modification)
        assertNull(assertNotNull(table.get(withoutText._id)).modification)
    }

    // ---------------------------------------------------------------- 2. indexes

    /**
     * `@Index` and `@IndexSet` on the audit records are load-bearing: an investigation reads these
     * tables by `requestId` or by `(modelId, recordId)` across a table that grows once per query and
     * once per disclosed record. An annotation that never becomes DDL is invisible until the table is
     * large enough for it to matter.
     *
     * Asserted against `pg_indexes`, which is the server's own account of what exists, rather than
     * against anything the driver reports back.
     */
    @Test
    fun `the declared indexes are created on the real table`() = runBlocking {
        table("RealDbIndexedAccess", DataAccessRecord.serializer())
        table(DISCLOSURES, DisclosureRecord.serializer())
        table("RealDbIndexedRequest", RequestRecord.serializer())

        val access = indexedColumnSets("RealDbIndexedAccess")
        for (column in listOf("requestid", "executionid", "modelid")) {
            assertTrue(
                access.any { it == listOf(column) },
                "DataAccessRecord.$column is annotated @Index but no index on it was created: $access",
            )
        }

        val disclosure = indexedColumnSets(DISCLOSURES)
        assertTrue(
            disclosure.any { it == listOf("modelid", "recordid") },
            "DisclosureRecord's @IndexSet(modelId, recordId) was not created: $disclosure",
        )
        assertTrue(
            disclosure.any { it == listOf("requestid") },
            "DisclosureRecord.requestId is annotated @Index but no index on it was created: $disclosure",
        )

        val request = indexedColumnSets("RealDbIndexedRequest")
        for (column in listOf("parentrequestid", "rootexecutionid", "principal")) {
            assertTrue(
                request.any { it == listOf(column) },
                "RequestRecord.$column is annotated @Index but no index on it was created: $request",
            )
        }
    }

    /**
     * The point of the `(modelId, recordId)` index is "everything ever disclosed about this record".
     * Creating the index is only half of it; this checks the planner will actually use it, which is
     * what fails when the column type and the queried value disagree.
     */
    @Test
    fun `a disclosure lookup by record uses the composite index`() = runBlocking {
        val table = table(DISCLOSURES, DisclosureRecord.serializer())
        val target = Uuid.random()
        table.insert(
            (0 until 2000).map {
                DisclosureRecord(
                    _id = Uuid.random(),
                    requestId = Uuid.random(),
                    modelId = it % 8,
                    recordId = if (it == 7) target else Uuid.random(),
                )
            }
        )
        analyze(DISCLOSURES)

        val plan = explain(
            "SELECT * FROM \"${actualTableName(DISCLOSURES)}\" " +
                "WHERE \"${actualColumnName(DISCLOSURES, "modelId")}\" = 7 " +
                "AND \"${actualColumnName(DISCLOSURES, "recordId")}\" = '$target'"
        )
        // The named index specifically, not merely "some index": `modelId` is the leading column of
        // the composite one but is also cheap to reach other ways, so a plan that just says "Index
        // Scan" would pass even for a lookup the composite index is useless for.
        assertTrue(
            "byrecord" in plan.lowercase(),
            "the by-record lookup did not use the byRecord index:\n$plan",
        )
    }

    /**
     * `disclosedAll` and `disclosedAny` are the queries an investigation actually runs, and they are
     * the only place this package emits a non-trivial condition: bitwise tests over two `Int` columns,
     * spanning both when the field indices do. A real backend has to translate those into SQL, whereas
     * the in-memory database evaluates the very predicate the helpers were written against and so
     * cannot disagree with them.
     */
    @Test
    fun `field-bit queries answer the same on a real backend`() = runBlocking {
        val table = table(DISCLOSURES, DisclosureRecord.serializer())
        // Indices chosen to straddle the column boundary: two in fields0, one in fields1.
        val ssn = 3
        val dob = 5
        val far = 40
        // A model id no other test in this file uses, since they share the table.
        val modelId = 900

        fun disclosure(vararg indices: Int): DisclosureRecord {
            val bits = FieldBits.of(indices.toList())
            return DisclosureRecord(
                _id = Uuid.random(),
                requestId = Uuid.random(),
                modelId = modelId,
                fields0 = bits.column(0),
                fields1 = bits.column(1),
                recordId = Uuid.random(),
            )
        }

        val both = disclosure(ssn, dob)
        val ssnOnly = disclosure(ssn)
        val farOnly = disclosure(far)
        val nothing = disclosure()
        table.insert(listOf(both, ssnOnly, farOnly, nothing))

        // Scoped to this test's own rows, since the table is shared.
        fun mine(bits: Condition<DisclosureRecord>) =
            Condition.And(listOf(condition<DisclosureRecord> { it.modelId.eq(modelId) }, bits))

        suspend fun ids(bits: Condition<DisclosureRecord>) =
            table.find(mine(bits)).toList().map { it._id }.toSet()

        assertEquals(setOf(both._id), ids(disclosedAll(listOf(ssn, dob))))
        assertEquals(setOf(both._id, ssnOnly._id, farOnly._id), ids(disclosedAny(listOf(ssn, dob, far))))
        // Straddling the columns must be an AND across them, not a union of the two.
        assertEquals(emptySet(), ids(disclosedAll(listOf(ssn, far))))
        assertEquals(setOf(farOnly._id), ids(disclosedAll(listOf(far))))
        // The documented degenerate cases: every set contains none of nothing, and none contains one.
        assertEquals(4, table.count(mine(disclosedAll(emptyList()))))
        assertEquals(0, table.count(mine(disclosedAny(emptyList()))))
    }

    // ---------------------------------------------------------------- 3. request lifecycle

    /**
     * A request is written twice: once at the start, fail-closed, with no outcome, and once at the
     * end to fill in outcome and duration. The second write is an `updateOne` matched on the id, and
     * on a real backend it is a genuine `UPDATE ... WHERE` rather than a map replacement.
     */
    @Test
    fun `a request record's two-write lifecycle completes`() = runBlocking {
        val table = table("RealDbRequestLifecycle", RequestRecord.serializer())
        val id = Uuid.generateV7NonMonotonicAt(Instant.fromEpochMilliseconds(1_700_000_123_456))

        table.insert(listOf(request(id)))
        val opened = assertNotNull(table.get(id))
        assertNull(opened.outcome, "the opening write must not claim an outcome")
        assertNull(opened.durationMs)

        val changed = table.updateOne(
            condition { it._id.eq(id) },
            modification { it.outcome assign "200"; it.durationMs assign 42L },
        )

        assertEquals("200", assertNotNull(changed.new).outcome)
        val closed = assertNotNull(table.get(id))
        assertEquals("200", closed.outcome)
        assertEquals(42L, closed.durationMs)
        // The id carries the instant; a rewritten row must not disturb it.
        assertEquals(Instant.fromEpochMilliseconds(1_700_000_123_456), closed.at)
        assertEquals(1, table.count(Condition.Always), "the completion write inserted a second row")
    }

    /**
     * The design requires a duplicate execution id to be a hard failure rather than a silent merge of
     * two principals' activity under one identifier — see the `_id` note on [RequestRecord]. On a
     * real backend that is a primary-key violation; an in-memory map would simply overwrite.
     */
    @Test
    fun `a duplicate request id fails loudly`() = runBlocking {
        val table = table("RealDbRequestDuplicate", RequestRecord.serializer())
        val id = Uuid.random()
        table.insert(listOf(request(id)))

        assertFailsWith<UniqueViolationException> {
            table.insert(listOf(request(id, endpoint = "/other")))
        }

        val surviving = table.find(Condition.Always).toList().single()
        assertEquals("/x", surviving.endpoint, "the rejected insert overwrote the original row")
    }

    // ---------------------------------------------------------------- 4. uuid primary keys

    /**
     * Every audit record is keyed by `Uuid`, on the stated grounds that it is sixteen bytes and
     * indexable everywhere. Postgres has a native `uuid` type; a driver that fell back to text would
     * make each key ~36 bytes and change how it sorts, which matters because these keys are v7 and
     * their ordering is the time ordering.
     */
    @Test
    fun `uuid keys are stored as a native uuid column`() = runBlocking {
        table(DISCLOSURES, DisclosureRecord.serializer())

        assertEquals("uuid", columnType(DISCLOSURES, "_id"))
        assertEquals("uuid", columnType(DISCLOSURES, "requestId"))
        assertEquals("uuid", columnType(DISCLOSURES, "recordId"))
    }

    /** v7 ids sort by mint time, and that only holds if the column sorts them as uuids. */
    @Test
    fun `v7 keys come back in mint order`() = runBlocking {
        val table = table("RealDbUuidOrder", DataAccessRecord.serializer())
        val instants = listOf(1_700_000_000_000L, 1_700_000_050_000L, 1_700_000_100_000L)
        val ids = instants.map { Uuid.generateV7NonMonotonicAt(Instant.fromEpochMilliseconds(it)) }
        // Inserted out of order, so a table that preserved insertion order would fail this.
        table.insert(listOf(ids[2], ids[0], ids[1]).map { dataAccess(id = it, condition = "{}") })

        val read = table.find(Condition.Always, orderBy = listOf(SortPart(DataAccessRecord.path._id))).toList()
        assertEquals(ids, read.map { it._id })
        assertEquals(instants.map { Instant.fromEpochMilliseconds(it) }, read.map { it.at })
    }

    // ---------------------------------------------------------------- 5. volume

    /**
     * Not a benchmark — evidence that the table this branch will write to most does not fall over.
     * A few thousand rows with realistic condition text, then the query an investigation actually
     * runs against it.
     */
    @Test
    fun `a few thousand data access records insert and remain queryable`() = runBlocking {
        val table = table("RealDbVolume", DataAccessRecord.serializer())
        val requestId = Uuid.random()
        val total = 3000
        val ofInterest = 250

        // Batched because a single 3000-row insert says nothing about the shape of the real write
        // path, which appends a row at a time as queries happen.
        (0 until total).chunked(500).forEach { chunk ->
            table.insert(
                chunk.map {
                    dataAccess(
                        requestId = if (it < ofInterest) requestId else Uuid.random(),
                        condition = Json.encodeToString(
                            Condition.serializer(Patient.serializer()),
                            condition<Patient> { p -> p.ssn.eq("value-$it") },
                        ),
                        skip = it,
                        limit = 1,
                    )
                }
            )
        }

        assertEquals(total, table.count(Condition.Always))
        assertEquals(
            ofInterest,
            table.find(condition { it.requestId.eq(requestId) }).count(),
            "the indexed lookup this table exists for did not return the expected rows",
        )
        val walk = table.find(condition { it.requestId.eq(requestId) }).toList()
        assertEquals(ofInterest, walk.map { it.skip }.distinct().size, "recorded offsets collided")
    }

    // ---------------------------------------------------------------- 6. mutation records

    /**
     * `MutationRecord` carries the widest free text of any audit record: `old` and `new` hold whole
     * serialized models, whatever a user typed into them. It is also the only audit record with a
     * *nullable* indexed id — `requestId` is absent for a task or a schedule tick — and a backend
     * that coerced that to a zero uuid would invent a join to a request record that does not exist.
     */
    @Test
    fun `a mutation's serialized values round-trip and an absent request id stays absent`() = runBlocking {
        val table = table("RealDbMutation", MutationRecord.serializer())

        val before = """{"_id":"x","name":"Ann \"The Brace\" O'Neil {x} \\ 🩺 日本語"}"""
        val after = """{"_id":"x","name":"changed"}"""
        val fromRequest = mutation(requestId = Uuid.random(), old = before, new = after)
        val fromSchedule = mutation(requestId = null, old = before, new = null)
        table.insert(listOf(fromRequest, fromSchedule))

        val loaded = assertNotNull(table.get(fromRequest._id))
        assertEquals(before, loaded.old)
        assertEquals(after, loaded.new)

        val scheduled = assertNotNull(table.get(fromSchedule._id))
        assertNull(scheduled.requestId, "an absent request id came back as a value that joins to nothing")
        assertNull(scheduled.new)
    }

    /**
     * The mutation table is read by "everything request X changed" and "everything ever done to this
     * record", so its five indexes are what make it usable at the volume its default produces.
     */
    @Test
    fun `the mutation record's declared indexes are created on the real table`() = runBlocking {
        table("RealDbIndexedMutation", MutationRecord.serializer())

        val indexes = indexedColumnSets("RealDbIndexedMutation")
        for (column in listOf("requestid", "executionid", "rootexecutionid", "initiatorkind", "modelid", "recordid")) {
            assertTrue(
                indexes.any { it == listOf(column) },
                "MutationRecord.$column is annotated @Index but no index on it was created: $indexes",
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun dataAccess(
        id: Uuid = Uuid.random(),
        requestId: Uuid = Uuid.random(),
        condition: String,
        modification: String? = null,
        skip: Int? = null,
        limit: Int? = null,
    ) = DataAccessRecord(
        _id = id,
        requestId = requestId,
        executionId = Uuid.random(),
        modelId = 1,
        operation = DataAccessOperation.Find,
        condition = condition,
        modification = modification,
        skip = skip,
        limit = limit,
    )

    private fun mutation(
        id: Uuid = Uuid.random(),
        requestId: Uuid?,
        old: String? = null,
        new: String? = null,
    ) = MutationRecord(
        _id = id,
        requestId = requestId,
        executionId = Uuid.random(),
        // Whatever wrote the row is what it attributes to; for a request-shaped one that is the
        // request itself, and for a schedule tick it is an id that resolves to nothing.
        attributedTo = requestId ?: Uuid.random(),
        rootExecutionId = Uuid.random(),
        initiatorKind = if (requestId == null) "schedule" else "http",
        initiator = """{"type":"direct"}""",
        modelId = 1,
        recordId = "x",
        operation = MutationOperation.Update,
        old = old,
        new = new,
    )

    private fun request(id: Uuid, endpoint: String = "/x") = RequestRecord(
        _id = id,
        rootExecutionId = id,
        sourceIp = "1.2.3.4",
        endpoint = endpoint,
        method = "GET",
    )

    /** Prepares [name] the way the deploy-time task does — `Database.prepare`, which creates the DDL. */
    private suspend fun <T : Any> table(name: String, serializer: KSerializer<T>): Table<T> =
        RealPostgres.database.prepare(DatabaseTableDefinition(serializer, name))

    /**
     * The column lists of every index on [table], lowercased.
     *
     * Case is dropped throughout these metadata helpers because whether the driver quotes its
     * identifiers (preserving `requestId`) or lets Postgres fold them (`requestid`) is the driver's
     * business, not something this test should pin.
     */
    private fun indexedColumnSets(table: String): List<List<String>> =
        RealPostgres.query(
            "SELECT indexdef FROM pg_indexes WHERE lower(tablename) = lower('$table')"
        ) { rows ->
            buildList {
                while (rows.next()) {
                    val columns = rows.getString(1).substringAfterLast('(').substringBeforeLast(')')
                    add(columns.split(",").map { it.trim().trim('"').lowercase() })
                }
            }
        }

    private fun columnType(table: String, column: String): String? =
        RealPostgres.query(
            "SELECT data_type FROM information_schema.columns " +
                "WHERE lower(table_name) = lower('$table') AND lower(column_name) = lower('$column')"
        ) { if (it.next()) it.getString(1) else null }

    /** The table's name as Postgres actually spells it, so a query can quote it correctly. */
    private fun actualTableName(table: String): String =
        RealPostgres.query(
            "SELECT tablename FROM pg_tables WHERE lower(tablename) = lower('$table')"
        ) { if (it.next()) it.getString(1) else error("table $table was never created") }

    /** The column's name as Postgres actually spells it. */
    private fun actualColumnName(table: String, column: String): String =
        RealPostgres.query(
            "SELECT column_name FROM information_schema.columns " +
                "WHERE lower(table_name) = lower('$table') AND lower(column_name) = lower('$column')"
        ) { if (it.next()) it.getString(1) else error("column $table.$column was never created") }

    private fun analyze(table: String) = RealPostgres.execute("""ANALYZE "${actualTableName(table)}"""")

    private fun explain(sql: String): String =
        RealPostgres.query("EXPLAIN $sql") { rows ->
            buildString { while (rows.next()) appendLine(rows.getString(1)) }
        }
}

/**
 * A single embedded Postgres cluster for this file, started at most once.
 *
 * The start attempt is kept as a [Result] rather than rethrown, so a host that cannot run it reports
 * the same skip reason on every test instead of a cascade of unrelated failures. Zonky registers a
 * JVM shutdown hook that stops the process and removes its data directory, and Gradle test workers
 * are their own JVM, so no explicit teardown is needed.
 */
private object RealPostgres {
    private val attempt: Result<EmbeddedPostgres> by lazy { runCatching { EmbeddedPostgres.start() } }

    /** Null when the cluster is usable; otherwise the reason every test in this file skips. */
    val unavailableReason: String?
        get() = attempt.exceptionOrNull()?.let { "embedded Postgres could not start: $it" }

    val database: Database by lazy {
        // Referencing PostgresDatabase's companion is what registers the "postgresql" URL scheme.
        with(PostgresDatabase) {
            Database.Settings.postgres("postgres", "postgres", "localhost:${attempt.getOrThrow().port}/postgres")
        }("audit-realdb", TestSettingContext())
    }

    /** Runs [sql] straight against the cluster, to read what the server itself says it created. */
    fun <T> query(sql: String, read: (ResultSet) -> T): T =
        attempt.getOrThrow().postgresDatabase.connection.use { connection ->
            connection.createStatement().use { statement -> statement.executeQuery(sql).use(read) }
        }

    fun execute(sql: String) {
        attempt.getOrThrow().postgresDatabase.connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }
}
