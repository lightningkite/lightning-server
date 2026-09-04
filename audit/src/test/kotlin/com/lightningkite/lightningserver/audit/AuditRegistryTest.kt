package com.lightningkite.lightningserver.audit

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The registry is the only record of what a bit means, so its one hard promise is that an
 * assignment, once made, never changes. These tests are mostly about what stays put across
 * successive deploys of a changing model.
 */
class AuditRegistryTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val models = database.registerTable("AuditModelRegistration", AuditModelRegistration.serializer())
        val fields = database.registerTable("AuditFieldRegistration", AuditFieldRegistration.serializer())
    }

    /** Runs [block] against a fresh in-memory database. */
    private fun onServer(block: suspend context(ServerRuntime) Fixture.() -> Unit) = runBlocking {
        TestServer.test(settings = { database set Database.Settings() }) {
            block(serverRuntime, Fixture())
        }
    }

    private class Fixture {
        context(server: ServerRuntime)
        suspend fun deploy(vararg serializers: KSerializer<*>) = reconcileAuditRegistry(
            TestServer.models(),
            TestServer.fields(),
            serializers.flatMap { it.descriptor.auditedModels().entries }.associate { it.key to it.value },
        )

        context(server: ServerRuntime)
        suspend fun registry() = loadAuditRegistry(TestServer.models(), TestServer.fields())

        context(server: ServerRuntime)
        suspend fun allFields() = TestServer.fields().find(Condition.Always).toList()
    }

    /** Collects WARN lines from the registry's logger for the duration of a test. */
    private class Warnings {
        private val logger = LoggerFactory.getLogger(
            "com.lightningkite.lightningserver.audit.AuditRegistry"
        ) as Logger
        private val appender = ListAppender<ILoggingEvent>().apply { start() }

        init {
            logger.addAppender(appender)
        }

        fun messages(): List<String> = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

        fun stop() {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun captureWarnings(): Warnings = Warnings()

    @Test
    fun `a first deploy assigns bits in declaration order`() = onServer {
        deploy(VersionedV1.serializer())

        val registry = registry()
        val modelId = registry.modelId("Versioned")
        assertEquals(mapOf("a" to 0), registry.fields(modelId))
    }

    @Test
    fun `deploying the same model again changes nothing`() = onServer {
        deploy(VersionedV1.serializer())
        val before = allFields().sortedBy { it.bitIndex }
        deploy(VersionedV1.serializer())

        assertEquals(before, allFields().sortedBy { it.bitIndex }, "a re-deploy must be a no-op")
    }

    @Test
    fun `a new field takes the next bit and leaves the existing ones alone`() = onServer {
        deploy(VersionedV1.serializer())
        deploy(VersionedV2.serializer())

        val registry = registry()
        val modelId = registry.modelId("Versioned")
        assertEquals(mapOf("a" to 0, "b" to 1), registry.fields(modelId))
    }

    /**
     * A rename is indistinguishable from a removal plus an addition, and that is fine: the old bit
     * keeps its old meaning for records written before the rename, and the new field gets a new one.
     * Nothing written in the past changes meaning, which is the only property that matters.
     */
    @Test
    fun `a renamed field gets a new bit while the old one stays resolvable`() = onServer {
        deploy(VersionedV2.serializer())
        deploy(VersionedV3.serializer())

        val registry = registry()
        val modelId = registry.modelId("Versioned")
        assertEquals(
            mapOf("a" to 0, "b" to 1, "renamed" to 2),
            registry.fields(modelId),
            "the retired name must still resolve, and the new one must not reuse its bit",
        )
    }

    @Test
    fun `a nested audited model gets its own id rather than bits on its parent`() = onServer {
        deploy(Patient.serializer())

        val registry = registry()
        val patient = registry.modelId(Patient.serializer().descriptor.serialName)
        val doctor = registry.modelId(Doctor.serializer().descriptor.serialName)
        assertTrue(patient != doctor, "the two models shared an id")
        assertEquals(setOf("name"), registry.fields(doctor).keys)
        assertTrue("doctor" in registry.fields(patient).keys, "the parent still records that a doctor was disclosed")
    }

    @Test
    fun `an audited model with no id is rejected at deploy`() = onServer {
        val failure = assertFailsWith<IllegalStateException> { deploy(Anonymous.serializer()) }
        assertTrue("_id" in failure.message.orEmpty(), "the message should name what is missing; was ${failure.message}")
    }

    /**
     * One disclosure row per record disclosed makes the id column the most-written column in the
     * system, so a string key is rejected rather than quietly accepted.
     */
    @Test
    fun `an audited model not keyed by Uuid is rejected at deploy`() = onServer {
        val failure = assertFailsWith<IllegalStateException> { deploy(StringKeyed.serializer()) }
        assertTrue(
            "Uuid" in failure.message.orEmpty(),
            "the message should name the required key type; was ${failure.message}",
        )
    }

    @Test
    fun `a model too wide for the available bits is rejected with the remedy`() = onServer {
        val failure = assertFailsWith<IllegalStateException> { deploy(Wide.serializer()) }
        assertTrue(
            "@Audited" in failure.message.orEmpty() && "reused" in failure.message.orEmpty(),
            "the message should name the way out and why bits are scarce; was ${failure.message}",
        )
    }

    /**
     * Running out of bits fails a deploy, and finding out then is the worst possible time. Bits are
     * consumed permanently, so a model creeps towards the ceiling rather than jumping at it.
     */
    @Test
    fun `a model approaching the bit ceiling is warned about while there is room to act`() = onServer {
        val warnings = captureWarnings()
        try {
            deploy(Roomy.serializer())
        } finally {
            warnings.stop()
        }

        assertTrue(
            warnings.messages().any { "Roomy" in it && "50 of 64" in it },
            "expected a capacity warning naming the model and its usage; saw ${warnings.messages()}",
        )
    }

    @Test
    fun `a model with room to spare is not warned about`() = onServer {
        val warnings = captureWarnings()
        try {
            deploy(VersionedV1.serializer())
        } finally {
            warnings.stop()
        }

        assertEquals(emptyList(), warnings.messages())
    }

    @Test
    fun `an unregistered model fails loudly rather than being recorded as unknown`() = onServer {
        deploy(VersionedV1.serializer())
        assertFailsWith<IllegalStateException> { registry().modelId("NeverDeployed") }
    }
}
