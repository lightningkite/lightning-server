package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.sessions.install
import com.lightningkite.lightningserver.EngineApi
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.lightningserver.runtime.handleRoot
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.services.data.Unsafe
import com.lightningkite.services.data.UuidV7
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.executePreDeployTasks
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.allRegisteredTables
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.EntryChange
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.uuid.Uuid

class AuditingBuilderTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())

        object Audit : AuditingBuilder.WithStandardRequestInfo(database) {
            val disclosures = install(AuditDisclosureLog())
            val mutations = AuditMutationLog()
            val mutationsAgain = AuditMutationLog()
        }

        val audit = path.path("audit") include Audit

        init {
            registerBasicMediaTypeCoders()
        }

        val ada = Patient(_id = Patient.ID(Uuid.parse("00000000-0000-0000-0000-0000000000a1")), name = "Ada", ssn = "secret")

        val patient = path.path("patient").get bind ApiHttpHandler(
            summary = "Patient",
            auth = noAuth,
            implementation = { _: Unit -> ada },
        )
    }

    private fun auditTables(server: ServerBuilder) =
        server.build().allRegisteredTables.keys.filter { it.startsWith("Audit_") }.toSet()

    private val shared = setOf("Audit_OriginRecords", "Audit_ModelRegistrations", "Audit_FieldRegistrations")

    @Test
    fun `only the logs that were called register tables`() {
        assertEquals(shared + setOf("Audit_DisclosureRecords", "Audit_MutationRecords"), auditTables(TestServer))
    }

    @Test
    fun `the shared parts alone register only origins and the registry`() {
        val tables = auditTables(object : ServerBuilder() {
            val database = setting("database", Database.Settings())
            val audit = path.path("audit") include object : AuditingBuilder.WithStandardRequestInfo(database) {}
        })

        assertEquals(shared, tables)
    }

    @Test
    fun `every log registers its own table`() {
        val tables = auditTables(object : ServerBuilder() {
            val database = setting("database", Database.Settings())
            val audit = path.path("audit") include object : AuditingBuilder.WithStandardRequestInfo(database) {
                val disclosures = install(AuditDisclosureLog())
                val authEvents = install(AuthEventLog())
                val mutations = AuditMutationLog()
                val queries = AuditQueryLog()
            }
        })

        assertEquals(
            shared + setOf("Audit_DisclosureRecords", "Audit_AuthEventRecords", "Audit_MutationRecords", "Audit_QueryRecords"),
            tables,
        )
    }

    @Test
    fun `calling a log builder twice returns the same log`() {
        assertSame(TestServer.Audit.mutations, TestServer.Audit.mutationsAgain)
    }

    // SAFETY: A fixed, valid v7 id, so the records it keys can be asserted on by value.
    @OptIn(InternalLightningServerApi::class, Unsafe::class)
    private val requestId = Execution.ID(UuidV7.fromRaw(Uuid.parse("00000000-0000-7000-8000-000000000101")))

    @OptIn(EngineApi::class)
    @Test
    fun `an installed disclosure log records a real request`() = runBlocking {
        TestServer.test(settings = { database set Database.Settings() }) {
            // Claude: pre-deploy assigns the bits a disclosure is read through.
            executePreDeployTasks()
            val request = HttpRequest<PathSpec>(
                path = RawHttpEndpoint(asString = "/patient", method = HttpMethod.GET),
                queryParameters = QueryParameters.EMPTY,
                headers = HttpHeaders.EMPTY,
                domain = "example.com",
                protocol = "https",
                sourceIp = "10.0.0.1",
            )
            assertEquals(HttpStatus.OK, engine.handleRoot(request, requestId).status)

            execute {
                val audit = TestServer.Audit
                val disclosure = audit.disclosures.table().find(Condition.Always).toList().single()
                assertEquals(requestId.originId, disclosure.origin)
                assertEquals(TestServer.ada._id.asUuid, disclosure.recordId)

                val byBit = audit.registry.assignments.await().fields(disclosure.recordType).entries.associate { it.value to it.key }
                assertEquals(setOf("name", "ssn"), disclosure.disclosed.map { byBit.getValue(it) }.toSet())

                assertEquals(listOf(requestId.originId), audit.origins.table().find(Condition.Always).toList().map { it._id })
            }
        }
    }

    @Test
    fun `a log built by the helper records`() = runBlocking {
        TestServer.test(settings = { database set Database.Settings() }) {
            executePreDeployTasks()
            execute {
                val audit = TestServer.Audit
                audit.registry.register(listOf(Patient.serializer().descriptor))
                val ada = Patient(_id = Patient.ID(Uuid.random()), name = "Ada", ssn = "1")

                audit.mutations.logAuditedFieldChanges(Patient.serializer(), listOf(EntryChange(null, ada)))

                assertEquals(listOf(ada._id.asUuid), audit.mutations.table().find(Condition.Always).toList().map { it.modelId })
            }
        }
    }
}
