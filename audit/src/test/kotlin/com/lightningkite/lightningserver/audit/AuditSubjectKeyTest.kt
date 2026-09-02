package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.PreDeployTask
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Database
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.uuid.Uuid

/**
 * The erasure decision cannot be retrofitted — records written before a subject key is registered
 * were written unwrapped and stay unshreddable. So a deployment that will need erasure has to be able
 * to prove, at deploy time, that the decision was made for every audited model.
 *
 * Note what this does *not* test: crypto-shredding, which is not implemented. See 11.2.
 */
class AuditSubjectKeyTest {

    private val sample = Patient(_id = Uuid.parse("00000000-0000-0000-0000-0000000000c1"), name = "", ssn = "")

    private open inner class Base(
        keys: Map<String, AuditSubjectKey<*>>,
        require: Boolean,
    ) : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())
        val audit = path.path("audit") include DisclosureAudit(
            database = database,
            subjectKeys = keys,
            requireSubjectKeys = require,
        )

        init {
            registerBasicMediaTypeCoders()
        }

        val patient = path.path("patient").get bind ApiHttpHandler(
            summary = "Patient",
            auth = noAuth,
            implementation = { _: Unit -> sample },
        )
    }

    private fun runPreDeploy(server: ServerBuilder) = runBlocking {
        server.test(settings = { }) {
            val done = HashSet<PreDeployTask>()
            suspend fun run(task: PreDeployTask) {
                if (!done.add(task)) return
                task.dependencies().forEach { run(it) }
                with(serverRuntime) { task.execute() }
            }
            serverRuntime.server.preDeployTasks.values.forEach { run(it) }
        }
    }

    @Test
    fun `an audited model with no subject key fails the deploy when keys are required`() {
        val server = object : Base(keys = emptyMap(), require = true) {}

        try {
            runPreDeploy(server)
            fail("the deploy succeeded with an audited model that can never be erased")
        } catch (e: IllegalStateException) {
            assertTrue("AuditSubjectKey" in (e.message ?: ""), "unhelpful message: ${e.message}")
            assertTrue("Patient" in (e.message ?: ""), "the offending model was not named: ${e.message}")
        }
    }

    @Test
    fun `supplying a key for every audited model lets the deploy proceed`() {
        val server = object : Base(
            // Through the helper, which is the whole point of it existing: pairing a serial name
            // with a key by hand is what lets Patient's entry hold a Doctor's key and still compile.
            keys = mapOf(
                auditSubjectKey(Patient.serializer()) { it._id.toString() },
                auditSubjectKey(Doctor.serializer()) { it._id.toString() },
            ),
            require = true,
        ) {}

        runPreDeploy(server)
    }

    /** Off by default, because the US regime targeted first does not require erasure. */
    @Test
    fun `the check is off unless asked for`() {
        runPreDeploy(object : Base(keys = emptyMap(), require = false) {})
    }
}
