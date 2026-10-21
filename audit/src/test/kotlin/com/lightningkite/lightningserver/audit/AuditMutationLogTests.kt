package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.audit.AuditMutationRecord.Operation
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.executePreDeployTasks
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.data.Queryable
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.EntryChange
import com.lightningkite.services.database.*
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

class MutationLogTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val origins = OriginRecording(
            database.registerTable("OriginRecord", OriginRecord.serializer(OriginRecord.StandardRequestInfo.serializer())),
            OriginRecording.RequestInfo.Standard
        ).also { install(it) }
        val registry = path.path("registry") include AuditRegistry(
            database.registerTable("AuditModelRegistration", AuditModelRegistration.serializer()),
            database.registerTable("AuditFieldRegistration", AuditFieldRegistration.serializer()),
        )
        val mutationLog = AuditMutationLog(
            origins,
            registry,
            database.registerTable("AuditMutation", AuditMutationRecord.serializer()),
        )

        val patients = database.registerTable("Patient", Patient.serializer())

        /** Never registered, so a change to it cannot be recorded. */
        val orders = database.registerTable("Order", Order.serializer())
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

    @Test
    fun `removing an embedded audited model records null`() = onServer {
        val withHouse = appointment.copy(attending = Doctor(Doctor.ID(Uuid.random()), "House"))
        val record = log(Appointment.serializer(), EntryChange(withHouse, appointment)).single()

        assertEquals(JsonNull, record.json.getValue("attending"))
    }

    @Test
    fun `an unaudited map holding audited fields is recorded whole`() = onServer {
        val changed = appointment.copy(contacts = mapOf("home" to Phone("555", "h")))
        val record = log(Appointment.serializer(), EntryChange(appointment, changed)).single()

        assertEquals(setOf("contacts"), record.json.keys)
    }

    @Test
    fun `a change that touches no audited field writes no origin`() = onServer {
        log(Patient.serializer(), EntryChange(ada, ada.copy(tags = mapOf("a" to "b"))))

        assertEquals(emptyList(), TestServer.origins.table().find(Condition.Always).toList())
    }

    // ===================== through a logged table =====================

    context(server: ServerRuntime)
    private fun loggedPatients() = TestServer.patients().logAuditedFieldChanges(TestServer.mutationLog)

    context(server: ServerRuntime)
    private suspend fun operations() = TestServer.mutationLog.table().find(Condition.Always).toList().map { it.operation }

    @Test
    fun `each write through a logged table is recorded as the operation it was`() = onServer {
        val table = loggedPatients()
        val bob = ada.copy(_id = Patient.ID(Uuid.random()), name = "Bob")

        table.insertOne(ada)
        table.updateOneById(ada._id, modification { it.ssn assign "222" })
        table.replaceOneById(ada._id, ada.copy(name = "Ann"))
        table.upsertOneById(bob._id, bob)
        table.upsertOne(condition { it._id eq bob._id }, modification { it.ssn assign "333" }, bob)
        table.deleteOneById(ada._id)
        table.deleteMany(Condition.Always)

        assertEquals(
            listOf(Operation.Create, Operation.Modify, Operation.Modify, Operation.Create, Operation.Modify, Operation.Delete, Operation.Delete).groupingBy { it }.eachCount(),
            operations().groupingBy { it }.eachCount(),
        )
    }

    @Test
    fun `the ignoring variants record every row and return what the table alone would`() = onServer {
        val table = loggedPatients()
        val bob = ada.copy(_id = Patient.ID(Uuid.random()), name = "Bob")
        table.insert(listOf(ada, bob))

        assertEquals(2, table.updateManyIgnoringResult(Condition.Always, modification { it.ssn assign "x" }))
        assertEquals(false, table.updateOneIgnoringResult(condition { it.name eq "Nobody" }, modification { it.ssn assign "y" }))
        assertEquals(true, table.deleteOneIgnoringOld(condition { it._id eq ada._id }))
        assertEquals(1, table.deleteManyIgnoringOld(Condition.Always))

        assertEquals(mapOf(Operation.Create to 2, Operation.Modify to 2, Operation.Delete to 2), operations().groupingBy { it }.eachCount())
    }

    @Test
    fun `reads through a logged table record nothing`() = onServer {
        val table = loggedPatients()
        TestServer.patients().insertOne(ada)

        table.find(Condition.Always).toList()
        table.count(Condition.Always)

        assertEquals(emptyList(), operations())
    }

    @Test
    fun `a change to an unregistered model stands, but the caller sees the failure`() = onServer {
        val order = Order(Order.ID(Uuid.random()), Payment.Card("4242"))

        assertFailsWith<IllegalStateException> {
            TestServer.orders().logAuditedFieldChanges(TestServer.mutationLog).insertOne(order)
        }
        assertEquals(listOf(order), TestServer.orders().find(Condition.Always).toList())
    }
}

@Queryable
data class Appointment(
    override val _id: ID,
    val note: String = "",
    val billing: Billing = Billing(),
    val attending: Doctor? = null,
    val consultants: List<Doctor> = listOf(),
    val payment: Payment? = null,
    val contacts: Map<String, Phone> = mapOf(),
) : AuditedModel<Appointment.ID> {
    @Serializable
    @JvmInline
    value class ID(override val raw: UuidV7) : AuditedModel.Id<UuidV7, ID> {
        override val asUuid: Uuid get() = raw.raw
    }
}

@Serializable
data class Billing(@Audited val account: String = "", val plan: String = "")
