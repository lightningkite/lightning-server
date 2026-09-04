package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.PreDeployTask
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.allRegisteredTables
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.uuid.Uuid

/**
 * The combination a deployment most likely wants — disclosure auditing without the firehose — run
 * rather than merely built.
 *
 * [AuditLayerIndependenceTest] asserts on which tables got registered, which catches a layer
 * dragging another one in but not a layer that needs the one it did not drag in. The disclosure log
 * reads its bit registry from [AuditCore], and the data access log is the layer that most obviously
 * *is* separable, so this drives a real disclosure with the data access log absent.
 */
class AuditLayerRuntimeIndependenceTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())

        val audit = path.path("audit") include AuditCore(database)
        val disclosureLog = path.path("audit-disclosure") include DisclosureLog(audit)

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
    }

    private val requestId = Uuid.parse("00000000-0000-4000-8000-000000000101")

    private fun request() = HttpRequest<PathSpec>(
        path = RawHttpEndpoint(asString = "/patient", method = HttpMethod.GET),
        queryParameters = QueryParameters.EMPTY,
        headers = HttpHeaders.EMPTY,
        domain = "example.com",
        protocol = "https",
        sourceIp = "10.0.0.1",
    )

    /** Assignment of bit indices happens in pre-deploy, so a disclosure cannot be read without it. */
    private suspend fun runPreDeployTasks(runtime: ServerRuntime) {
        val done = HashSet<PreDeployTask>()
        suspend fun run(task: PreDeployTask) {
            if (!done.add(task)) return
            task.dependencies().forEach { run(it) }
            with(runtime) { task.execute() }
        }
        runtime.server.preDeployTasks.values.forEach { run(it) }
    }

    @Test
    fun `a disclosure is recorded with the data access log absent`() = runBlocking {
        assertFalse(
            "AuditDataAccess" in TestServer.build().allRegisteredTables,
            "this test proves nothing if the data access log is present after all",
        )

        TestServer.test(settings = { database set Database.Settings(); cache set Cache.Settings() }) {
            runPreDeployTasks(serverRuntime)
            with(serverRuntime) {
                val response = handle(request(), requestId)
                assertEquals(HttpStatus.OK, response.status)

                val disclosure = TestServer.disclosureLog.disclosures().find(Condition.Always).toList().single()
                assertEquals(requestId, disclosure.requestId)
                assertEquals(TestServer.ada._id, disclosure.recordId)

                // The bits are readable, which is the part that actually depends on the core: the
                // registry the disclosure log resolves them through lives there, not here.
                val registry = TestServer.audit.registry.await()
                val byBit = registry.fields(disclosure.modelId).entries.associate { it.value to it.key }
                assertEquals(setOf("name", "ssn"), disclosure.fields.indices().map { byBit.getValue(it) }.toSet())

                // And the request record the disclosure points at was written, which is the other
                // thing the core supplies.
                assertEquals(
                    listOf(requestId),
                    TestServer.audit.requests().find(Condition.Always).toList().map { it._id },
                )
            }
        }
        Unit
    }
}
