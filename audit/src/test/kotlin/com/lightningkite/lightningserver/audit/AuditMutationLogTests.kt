package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.executePreDeployTasks
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.EntryChange
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class MutationLogTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val origins = OriginRecording(database).also { it.install() }
        val registry = path.path("registry") include AuditRegistry(
            database.registerTable("AuditModelRegistration", AuditModelRegistration.serializer()),
            database.registerTable("AuditFieldRegistration", AuditFieldRegistration.serializer()),
        )
        val mutationLog = AuditMutationLog(
            origins,
            registry,
            database.registerTable("AuditMutation", AuditMutationRecord.serializer()),
        )
    }

    private fun onServer(block: suspend context(ServerRuntime) () -> Unit) = runBlocking {
        TestServer.test(settings = { database set Database.Settings() }) {
            executePreDeployTasks()
            execute {
                TestServer.registry.register(listOf(Patient.serializer().descriptor, Appointment.serializer().descriptor))
                block(serverRuntime)
            }
        }
    }

    /** Logs [changes] and returns every record written so far. */
    context(server: ServerRuntime)
    private suspend fun <T : AuditedModel<*>> log(serializer: KSerializer<T>, vararg changes: EntryChange<T>): List<AuditMutationRecord> {
        TestServer.mutationLog.logAuditedFieldChanges(serializer, changes.toList())
        return TestServer.mutationLog.table().find(Condition.Always).toList()
    }

    private val AuditMutationRecord.json: JsonObject get() = Json.parseToJsonElement(data!!).jsonObject

    private val ada = Patient(_id = Patient.ID(Uuid.random()), name = "Ada", ssn = "111", address = Address("Main", "Provo"))
    private val appointment = Appointment(_id = Appointment.ID(UuidV7.generate()))

    // ===================== operations =====================

    @Test
    fun `a create records every audited field and nothing else`() = onServer {
        val record = log(Patient.serializer(), EntryChange(null, ada)).single()

        assertEquals(AuditMutationRecord.Operation.Create, record.operation)
        assertEquals(ada._id.raw, record.modelId)
        assertEquals(setOf("name", "ssn", "address", "phones", "doctor"), record.json.keys)
    }

    @Test
    fun `a modify records only the audited fields that changed`() = onServer {
        val record = log(Patient.serializer(), EntryChange(ada, ada.copy(ssn = "222", tags = mapOf("a" to "b")))).single()

        assertEquals(AuditMutationRecord.Operation.Modify, record.operation)
        assertEquals(setOf("ssn"), record.json.keys)
    }

    @Test
    fun `a delete is recorded without data`() = onServer {
        val record = log(Patient.serializer(), EntryChange(ada, null)).single()

        assertEquals(AuditMutationRecord.Operation.Delete, record.operation)
        assertEquals(ada._id.raw, record.modelId)
        assertNull(record.data)
    }

    @Test
    fun `a modify of only unaudited fields is not recorded`() = onServer {
        assertEquals(emptyList(), log(Patient.serializer(), EntryChange(ada, ada.copy(tags = mapOf("a" to "b")))))
    }

    @Test
    fun `changes that are not changes are not recorded`() = onServer {
        assertEquals(emptyList(), log(Patient.serializer(), EntryChange(ada, ada), EntryChange(null, null)))
    }

    @Test
    fun `each change in a call gets its own record`() = onServer {
        val bob = ada.copy(_id = Patient.ID(Uuid.random()), name = "Bob")
        val records = log(Patient.serializer(), EntryChange(null, ada), EntryChange(null, bob))

        assertEquals(setOf(ada._id.raw, bob._id.raw), records.map { it.modelId }.toSet())
    }

    @Test
    fun `the record names the model by its id's asUuid`() = onServer {
        val record = log(Appointment.serializer(), EntryChange(appointment, null)).single()

        assertEquals(appointment._id.raw.raw, record.modelId)
    }

    @Test
    fun `the record names the origin it was made under`() = onServer {
        val record = log(Patient.serializer(), EntryChange(null, ada)).single()

        assertEquals(serverRuntime.execution.origin.originId, record.origin)
    }

    // ===================== which fields count =====================

    @Test
    fun `an audited nested class records its changed fields, audited or not`() = onServer {
        val record = log(Patient.serializer(), EntryChange(ada, ada.copy(address = Address("Main", "Orem")))).single()

        assertEquals(setOf("city"), record.json.getValue("address").jsonObject.keys)
    }

    @Test
    fun `an audited field set to null is recorded as null`() = onServer {
        val record = log(Patient.serializer(), EntryChange(ada, ada.copy(address = null))).single()

        assertEquals(JsonNull, record.json.getValue("address"))
    }

    @Test
    fun `an unaudited nested class records only its audited fields`() = onServer {
        val changed = appointment.copy(billing = Billing(account = "acct", plan = "gold"))
        val record = log(Appointment.serializer(), EntryChange(appointment, changed)).single()

        assertEquals(setOf("billing"), record.json.keys)
        assertEquals(setOf("account"), record.json.getValue("billing").jsonObject.keys)
    }

    @Test
    fun `an unaudited nested class whose audited fields did not change is not recorded`() = onServer {
        assertEquals(emptyList(), log(Appointment.serializer(), EntryChange(appointment, appointment.copy(billing = Billing(plan = "gold")))))
    }

    @Test
    fun `an unaudited list holding audited fields is recorded whole`() = onServer {
        val record = log(Patient.serializer(), EntryChange(ada, ada.copy(phones = listOf(Phone("555", "home"))))).single()

        assertEquals(setOf("phones"), record.json.keys)
    }

    @Test
    fun `an unaudited sealed value holding audited fields is recorded whole`() = onServer {
        val record = log(Appointment.serializer(), EntryChange(appointment, appointment.copy(payment = Payment.Card("4242")))).single()

        assertEquals(setOf("payment"), record.json.keys)
    }

    @Test
    fun `an embedded audited model records its audited fields`() = onServer {
        val house = Doctor(Doctor.ID(Uuid.random()), "House")
        val added = log(Appointment.serializer(), EntryChange(appointment, appointment.copy(attending = house))).single()
        assertEquals(setOf("name"), added.json.getValue("attending").jsonObject.keys)

        val withHouse = appointment.copy(attending = house)
        val renamed = log(Appointment.serializer(), EntryChange(withHouse, withHouse.copy(attending = house.copy(name = "Wilson")))).single { it._id != added._id }
        assertEquals(setOf("name"), renamed.json.getValue("attending").jsonObject.keys)
    }

    @Test
    fun `a list of embedded audited models is recorded whole`() = onServer {
        val changed = appointment.copy(consultants = listOf(Doctor(Doctor.ID(Uuid.random()), "Cuddy")))
        val record = log(Appointment.serializer(), EntryChange(appointment, changed)).single()

        assertEquals(setOf("consultants"), record.json.keys)
    }
}

@Serializable
@GenerateDataClassPaths
data class Appointment(
    override val _id: ID,
    val note: String = "",
    val billing: Billing = Billing(),
    val attending: Doctor? = null,
    val consultants: List<Doctor> = listOf(),
    val payment: Payment? = null,
) : AuditedModel<Appointment.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: UuidV7) : AuditedModel.Id<UuidV7, ID> {
        override val asUuid: Uuid get() = raw.raw
    }
}

@Serializable
data class Billing(@Audited val account: String = "", val plan: String = "")
