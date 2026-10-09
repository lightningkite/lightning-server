package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.audit.disclosure.DisclosureLogInterceptor
import com.lightningkite.lightningserver.audit.disclosure.DisclosureLogInterceptor.Disclosure
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.Partial
import com.lightningkite.services.database.PartialSerializer
import com.lightningkite.services.database.partialOf
import com.lightningkite.services.database.serializableProperties
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Disclosures are asserted by **field path**, not by bit number, and the paths themselves are pinned
 * independently by [DescriptorWalkTest]. Asserting raw bits here would let the runtime walk and the
 * registration walk agree on the same wrong answer — the exact failure mode that hid the bitwise
 * condition bugs for so long.
 */
class DisclosureEncoderTest {

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val audit = path.path("audit") include AuditCore(database)

        // Claude: constructed but not installed; the tests call it directly.
        val interceptor = DisclosureLogInterceptor(
            audit.registry,
            audit.origins,
            database.registerTable("AuditDisclosure", DisclosureRecord.serializer()),
        )
    }

    private fun onServer(block: suspend context(ServerRuntime) Fixture.() -> Unit) = runBlocking {
        TestServer.test(settings = { database set Database.Settings() }) {
            execute { block(serverRuntime, Fixture()) }
        }
    }

    private class Fixture {
        lateinit var registry: AuditRegistry.Assignments

        context(server: ServerRuntime)
        suspend fun deploy(vararg serializers: KSerializer<*>) {
            TestServer.audit.registry.register(serializers.toList())
            registry = TestServer.audit.registry.load()
        }

        context(server: ServerRuntime)
        suspend fun <T> extract(serializer: KSerializer<T>, value: T): List<Disclosure> =
            TestServer.interceptor.calculateDisclosures(serializer, value)

        /** The disclosed field paths of one disclosure, resolved back through the registry. */
        fun paths(disclosure: Disclosure): Set<String> {
            val byBit = registry.fields(disclosure.model).entries.associate { it.value to it.key }
            return disclosure.disclosed.map { byBit.getValue(it) }.toSet()
        }

        fun modelIdOf(serializer: KSerializer<*>): ModelTypeId = registry.modelId(serializer.descriptor.serialName)
    }

    /** A `Patient` reduced to just the named fields, as `queryPartial` would return it. */
    private fun patientPartial(fields: Set<String>): Partial<Patient> = partialOf(
        Patient(_id = id, name = "Ada", ssn = "secret"),
        Patient.serializer().serializableProperties!!.filter { it.name in fields },
    )

    private val id = Uuid.parse("00000000-0000-0000-0000-0000000000a1")
    private val otherId = Uuid.parse("00000000-0000-0000-0000-0000000000b2")

    @Test
    fun `only fields that carried a value are recorded`() = onServer {
        deploy(Patient.serializer())
        val patient = Patient(_id = id, name = "Ada", ssn = "")

        val disclosure = extract(Patient.serializer(), patient).single()

        assertEquals(modelIdOf(Patient.serializer()), disclosure.model)
        assertEquals(id, disclosure.recordId)
        assertEquals(setOf("name"), paths(disclosure), "an empty ssn is a default, not a disclosure")
    }

    @Test
    fun `a record id wrapped in value classes is unwrapped to its Uuid`() = onServer {
        deploy(TypedKeyed.serializer(), DoublyTypedKeyed.serializer())

        val typed = extract(TypedKeyed.serializer(), TypedKeyed(TypedKeyed.ID(id), "a")).single()
        assertEquals(id, typed.recordId)
        assertEquals(setOf("value"), paths(typed))

        val doubly = extract(
            DoublyTypedKeyed.serializer(),
            DoublyTypedKeyed(DoublyTypedKeyed.Outer(TypedKeyed.ID(otherId)), "b"),
        ).single()
        assertEquals(otherId, doubly.recordId)
    }

    @Test
    fun `nulls, zeroes, empty strings and empty collections are all defaults`() = onServer {
        deploy(Reading.serializer())

        val disclosure = extract(Reading.serializer(), Reading(_id = id)).single()

        assertEquals(emptySet(), paths(disclosure), "a record with nothing tracked in its payload is still a record")
    }

    @Test
    fun `an enum discloses whatever value it holds`() = onServer {
        deploy(Reading.serializer())

        val disclosure = extract(Reading.serializer(), Reading(_id = id, severity = Severity.Low)).single()

        assertEquals(setOf("severity"), paths(disclosure), "the first enum entry is still a value")
    }

    @Test
    fun `a false boolean and a zero int are not disclosures but their opposites are`() = onServer {
        deploy(Reading.serializer())

        val quiet = extract(Reading.serializer(), Reading(_id = id, count = 0, flagged = false)).single()
        assertEquals(emptySet(), paths(quiet))

        val loud = extract(Reading.serializer(), Reading(_id = id, count = 3, flagged = true)).single()
        assertEquals(setOf("count", "flagged"), paths(loud))
    }

    @Test
    fun `a nested structure records the container and each leaf that carried a value`() = onServer {
        deploy(Patient.serializer())
        val patient = Patient(_id = id, name = "Ada", ssn = "x", address = Address(street = "1 Main", city = ""))

        val disclosure = extract(Patient.serializer(), patient).single()

        assertEquals(setOf("name", "ssn", "address", "address.street"), paths(disclosure))
    }

    /** The distinction the container bit exists for. */
    @Test
    fun `a present but all-default structure is distinguishable from an absent one`() = onServer {
        deploy(Patient.serializer())
        val base = Patient(_id = id, name = "Ada", ssn = "x")

        val absent = extract(Patient.serializer(), base).single()
        val empty = extract(Patient.serializer(), base.copy(address = Address("", ""))).single()

        assertTrue("address" !in paths(absent))
        assertTrue("address" in paths(empty))
    }

    @Test
    fun `list elements are recorded under the list path`() = onServer {
        deploy(Patient.serializer())
        val patient = Patient(_id = id, name = "Ada", ssn = "x", phones = listOf(Phone("555", "")))

        val disclosure = extract(Patient.serializer(), patient).single()

        assertEquals(
            setOf("name", "ssn", "phones[].number"),
            paths(disclosure),
            "the list itself is not itemised, but the annotated field inside it is",
        )
    }

    @Test
    fun `an empty list is not a disclosure`() = onServer {
        deploy(Patient.serializer())
        val patient = Patient(_id = id, name = "Ada", ssn = "x", phones = listOf())

        assertTrue("phones[].number" !in paths(extract(Patient.serializer(), patient).single()))
    }

    @Test
    fun `map values are recorded under the map path`() = onServer {
        deploy(Contact.serializer())
        val contact = Contact(_id = id, numbers = mapOf("home" to Phone("555", "h")))

        val disclosure = extract(Contact.serializer(), contact).single()

        assertEquals(
            setOf("numbers", "numbers{}.number"),
            paths(disclosure),
            "map values use a different path separator than list elements",
        )
    }

    @Test
    fun `a sealed field records the subclass it actually held`() = onServer {
        deploy(Order.serializer())
        val order = Order(_id = id, payment = Payment.Card(last4 = "4242"))

        val disclosure = extract(Order.serializer(), order).single()

        assertEquals(setOf("payment", "payment(Card).last4"), paths(disclosure))
    }

    @Test
    fun `a nested audited model produces its own record rather than bits on its parent`() = onServer {
        deploy(Patient.serializer(), Doctor.serializer())
        val patient = Patient(_id = id, name = "Ada", ssn = "x", doctor = Doctor(otherId, "Dr Who"))

        val disclosures = extract(Patient.serializer(), patient)

        assertEquals(2, disclosures.size, "expected one record each for the patient and the doctor")
        val doctor = disclosures.single { it.model == modelIdOf(Doctor.serializer()) }
        assertEquals(otherId, doctor.recordId)
        assertEquals(setOf("name"), paths(doctor))

        val patientDisclosure = disclosures.single { it.model == modelIdOf(Patient.serializer()) }
        assertTrue("doctor" in paths(patientDisclosure), "the parent still records that a doctor was disclosed")
    }

    @Test
    fun `every element of a list of audited models gets its own record`() = onServer {
        deploy(Patient.serializer())
        val patients = (1..5).map { Patient(_id = Uuid.random(), name = "P$it", ssn = "s") }

        val disclosures = extract(ListSerializer(Patient.serializer()), patients)

        assertEquals(5, disclosures.size)
        assertEquals(patients.map { it._id }.toSet(), disclosures.map { it.recordId }.toSet())
    }

    @Test
    fun `a value with nothing audited in it produces nothing`() = onServer {
        deploy(Patient.serializer())

        assertEquals(emptyList(), extract(Address.serializer(), Address("1 Main", "Springfield")))
    }

    @Test
    fun `a nullable audited model that is absent produces nothing`() = onServer {
        deploy(Patient.serializer())

        assertEquals(emptyList(), extract(Patient.serializer().nullable, null))
    }

    // --- Partials -------------------------------------------------------------------------------

    @Test
    fun `a partial records only the fields it actually carried`() = onServer {
        deploy(Patient.serializer())
        val partial = patientPartial(setOf("_id", "name"))

        val disclosure = extract(PartialSerializer(Patient.serializer()), partial).single()

        assertEquals(modelIdOf(Patient.serializer()), disclosure.model)
        assertEquals(id, disclosure.recordId)
        assertEquals(setOf("name"), paths(disclosure), "ssn was never sent, so it was not disclosed")
    }

    /**
     * One row per record means a disclosure has to name a record. A partial without `_id` cannot, so
     * it fails rather than recording an unattributable disclosure.
     */
    @Test
    fun `a partial without an id fails closed`() = onServer {
        deploy(Patient.serializer())
        val partial = patientPartial(setOf("name", "ssn"))

        val failure = assertFailsWith<IllegalStateException> {
            extract(PartialSerializer(Patient.serializer()), partial)
        }
        assertTrue("_id" in failure.message.orEmpty(), "the message should say what was missing; was ${failure.message}")
    }
}
