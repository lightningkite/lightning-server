package com.lightningkite.lightningserver.audit

import com.lightningkite.services.database.optics.*
import com.lightningkite.lightningserver.audit.AuditDisclosureLog.Disclosure
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.Partial
import com.lightningkite.services.database.PartialSerializer
import com.lightningkite.services.database.partialOf
import com.lightningkite.services.database.optics.fields
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.elementNames
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
        val registry = path.path("registry") include AuditRegistry(
            database.registerTable("AuditModelRegistration", AuditModelRegistration.serializer()),
            database.registerTable("AuditFieldRegistration", AuditFieldRegistration.serializer()),
        )

        // Claude: constructed but not installed; the tests call it directly.
        val interceptor = AuditDisclosureLog(
            OriginRecording(
                database.registerTable("OriginRecord", OriginRecord.serializer(OriginRecord.StandardRequestInfo.serializer())),
                OriginRecording.RequestInfo.Standard
            ),
            registry,
            database.registerTable("AuditDisclosure", AuditDisclosureRecord.serializer()),
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
            TestServer.registry.register(serializers.map { it.descriptor })
            registry = TestServer.registry.load()
        }

        context(server: ServerRuntime)
        suspend fun <T> extract(serializer: KSerializer<T>, value: T): List<Disclosure> =
            TestServer.interceptor.calculateDisclosures(serializer, value)

        /** The disclosed field paths of one disclosure, resolved back through the registry. */
        fun paths(disclosure: Disclosure): Set<String> {
            val byBit = registry.fields(disclosure.model).entries.associate { it.value to it.key }
            return disclosure.disclosed.map { byBit.getValue(it) }.toSet()
        }

        fun modelIdOf(serializer: KSerializer<*>): ModelTypeId = registry.modelType(serializer.descriptor)
    }

    /** A `Patient` reduced to just the named fields, as `queryPartial` would return it. */
    private fun patientPartial(fields: Set<String>): Partial<Patient> = partialOf(
        Patient(_id = Patient.ID(id), name = "Ada", ssn = "secret"),
        Patient.serializer().fields!!.filter { it.name in fields },
    )

    private val id = Uuid.parse("00000000-0000-0000-0000-0000000000a1")
    private val otherId = Uuid.parse("00000000-0000-0000-0000-0000000000b2")

    @Test
    fun `only fields that carried a value are recorded`() = onServer {
        deploy(Patient.serializer())
        val patient = Patient(_id = Patient.ID(id), name = "Ada", ssn = "")

        val disclosure = extract(Patient.serializer(), patient).single()

        assertEquals(modelIdOf(Patient.serializer()), disclosure.model)
        assertEquals(id, disclosure.recordId)
        assertEquals(setOf("name"), paths(disclosure), "an empty ssn is a default, not a disclosure")
    }

    @Test
    fun `the record id is the id's asUuid`() = onServer {
        deploy(TypedKeyed.serializer(), Appointment.serializer())

        val typed = extract(TypedKeyed.serializer(), TypedKeyed(TypedKeyed.ID(id), "a")).single()
        assertEquals(id, typed.recordId)
        assertEquals(setOf("value"), paths(typed))

        val v7 = UuidV7.generate()
        assertEquals(v7.raw, extract(Appointment.serializer(), Appointment(Appointment.ID(v7))).single().recordId)
    }

    @Test
    fun `an enum discloses whatever value it holds`() = onServer {
        deploy(Reading.serializer())

        val disclosure = extract(Reading.serializer(), Reading(_id = Reading.ID(id), severity = Severity.Low)).single()

        assertEquals(setOf("severity"), paths(disclosure), "the first enum entry is still a value")
    }

    @Test
    fun `a false boolean and a zero int are not disclosures but their opposites are`() = onServer {
        deploy(Reading.serializer())

        val quiet = extract(Reading.serializer(), Reading(_id = Reading.ID(id), count = 0, flagged = false)).single()
        assertEquals(emptySet(), paths(quiet))

        val loud = extract(Reading.serializer(), Reading(_id = Reading.ID(id), count = 3, flagged = true)).single()
        assertEquals(setOf("count", "flagged"), paths(loud))
    }

    @Test
    fun `a value class is judged on the value it wraps`() = onServer {
        deploy(Reading.serializer())

        assertEquals(emptySet(), paths(extract(Reading.serializer(), Reading(_id = Reading.ID(id), code = Reading.Code(0))).single()))
        assertEquals(setOf("code"), paths(extract(Reading.serializer(), Reading(_id = Reading.ID(id), code = Reading.Code(7))).single()))
    }

    @Test
    fun `the most empty value of every type is not a disclosure`() = onServer {
        deploy(Emptiness.serializer())

        assertEquals(emptySet(), paths(extract(Emptiness.serializer(), Emptiness(_id = Emptiness.ID(id))).single()))
    }

    @Test
    fun `a non-empty value of every type is a disclosure`() = onServer {
        deploy(Emptiness.serializer())
        val full = Emptiness(
            _id = Emptiness.ID(id),
            boolean = true,
            byte = 1,
            short = 1,
            int = 1,
            long = 1,
            float = 1f,
            double = 1.0,
            char = 'a',
            string = "a",
            list = listOf("a"),
            set = setOf("a"),
            map = mapOf("a" to 1),
            wrapped = Emptiness.Wrapped("a"),
            nullableInt = 1,
            nullableString = "a",
            nullableList = listOf("a"),
            nullableWrapped = Emptiness.Wrapped("a"),
        )

        assertEquals(
            Emptiness.serializer().descriptor.elementNames.toSet() - "_id",
            paths(extract(Emptiness.serializer(), full).single()),
        )
    }

    @Test
    fun `negative zero is as empty as zero but NaN is not`() = onServer {
        deploy(Emptiness.serializer())

        val negativeZero = extract(Emptiness.serializer(), Emptiness(_id = Emptiness.ID(id), float = -0f, double = -0.0)).single()
        assertEquals(emptySet(), paths(negativeZero))

        val nan = extract(Emptiness.serializer(), Emptiness(_id = Emptiness.ID(id), float = Float.NaN, double = Double.NaN)).single()
        assertEquals(setOf("float", "double"), paths(nan))
    }

    @Test
    fun `a non-null value in a nullable field is a disclosure however empty`() = onServer {
        deploy(Emptiness.serializer())
        val cases = mapOf(
            "nullableInt" to Emptiness(_id = Emptiness.ID(id), nullableInt = 0),
            "nullableString" to Emptiness(_id = Emptiness.ID(id), nullableString = ""),
            "nullableList" to Emptiness(_id = Emptiness.ID(id), nullableList = listOf()),
            "nullableWrapped" to Emptiness(_id = Emptiness.ID(id), nullableWrapped = Emptiness.Wrapped("")),
        )

        for ((field, value) in cases) {
            assertEquals(setOf(field), paths(extract(Emptiness.serializer(), value).single()), field)
        }
    }

    @Test
    fun `a collection of empty elements is a disclosure`() = onServer {
        deploy(Emptiness.serializer())
        val value = Emptiness(_id = Emptiness.ID(id), list = listOf(""), set = setOf(""), map = mapOf("" to 0))

        assertEquals(setOf("list", "set", "map"), paths(extract(Emptiness.serializer(), value).single()))
    }

    @Test
    fun `a nested structure records the container and each leaf that carried a value`() = onServer {
        deploy(Patient.serializer())
        val patient = Patient(_id = Patient.ID(id), name = "Ada", ssn = "x", address = Address(street = "1 Main", city = ""))

        val disclosure = extract(Patient.serializer(), patient).single()

        assertEquals(setOf("name", "ssn", "address", "address.street"), paths(disclosure))
    }

    /** The distinction the container bit exists for. */
    @Test
    fun `a present but all-default structure is distinguishable from an absent one`() = onServer {
        deploy(Patient.serializer())
        val base = Patient(_id = Patient.ID(id), name = "Ada", ssn = "x")

        val absent = extract(Patient.serializer(), base).single()
        val empty = extract(Patient.serializer(), base.copy(address = Address("", ""))).single()

        assertTrue("address" !in paths(absent))
        assertTrue("address" in paths(empty))
    }

    @Test
    fun `list elements are recorded under the list path`() = onServer {
        deploy(Patient.serializer())
        val patient = Patient(_id = Patient.ID(id), name = "Ada", ssn = "x", phones = listOf(Phone("555", "")))

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
        val patient = Patient(_id = Patient.ID(id), name = "Ada", ssn = "x", phones = listOf())

        assertTrue("phones[].number" !in paths(extract(Patient.serializer(), patient).single()))
    }

    @Test
    fun `map values are recorded under the map path`() = onServer {
        deploy(Contact.serializer())
        val contact = Contact(_id = Contact.ID(id), numbers = mapOf("home" to Phone("555", "h")))

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
        val order = Order(_id = Order.ID(id), payment = Payment.Card(last4 = "4242"))

        val disclosure = extract(Order.serializer(), order).single()

        assertEquals(setOf("payment", "payment(Card).last4"), paths(disclosure))
    }

    @Test
    fun `a sealed field with same-named fields records only the subclass it held`() = onServer {
        deploy(Subscriber.serializer())

        val email = extract(Subscriber.serializer(), Subscriber(_id = Subscriber.ID(id), via = Channel.Email("a@b.c"))).single()
        assertEquals(setOf("via", "via(Email).address"), paths(email))

        val post = extract(Subscriber.serializer(), Subscriber(_id = Subscriber.ID(id), via = Channel.Post("1 Main"))).single()
        assertEquals(setOf("via", "via(Post).address"), paths(post))
    }

    @Test
    fun `a subclass without a SerialName is pathed by its qualified name`() = onServer {
        deploy(Subscriber.serializer())
        val name = Channel.Pigeon.serializer().descriptor.serialName

        val disclosure = extract(Subscriber.serializer(), Subscriber(_id = Subscriber.ID(id), via = Channel.Pigeon("roof"))).single()

        assertEquals(setOf("via", "via($name).loft"), paths(disclosure))
    }

    @Test
    fun `sealed elements of a list record the subclass each held`() = onServer {
        deploy(Subscriber.serializer())
        val subscriber = Subscriber(
            _id = Subscriber.ID(id),
            via = Channel.Email(""),
            fallbacks = listOf(Channel.Post("1 Main"), Channel.Email("")),
        )

        val disclosure = extract(Subscriber.serializer(), subscriber).single()

        assertEquals(setOf("via", "fallbacks[](Post).address"), paths(disclosure))
    }

    @Test
    fun `an audited subclass sent as its sealed parent is recorded as the subclass`() = onServer {
        deploy(Visit.Checkup.serializer(), Visit.Surgery.serializer())

        val disclosure = extract(Visit.serializer(), Visit.Surgery(Visit.Surgery.ID(id), "appendix")).single()

        assertEquals(modelIdOf(Visit.Surgery.serializer()), disclosure.model)
        assertEquals(id, disclosure.recordId)
        assertEquals(setOf("notes"), paths(disclosure))
    }

    @Test
    fun `the record id is the _id, not another Uuid the model carries`() = onServer {
        deploy(Referencing.serializer(), Doctor.serializer())
        val decoys = List(5) { Uuid.random() }
        val value = Referencing(
            before = decoys[0],
            typedBefore = TypedKeyed.ID(decoys[1]),
            doctor = Doctor(Doctor.ID(decoys[2]), "Dr Who"),
            _id = Referencing.ID(id),
            after = decoys[3],
            typedAfter = TypedKeyed.ID(decoys[4]),
            nested = Doctor.Ref(otherId),
        )

        val disclosures = extract(Referencing.serializer(), value)

        assertEquals(id, disclosures.single { it.model == modelIdOf(Referencing.serializer()) }.recordId)
        assertEquals(decoys[2], disclosures.single { it.model == modelIdOf(Doctor.serializer()) }.recordId)
    }

    @Test
    fun `a nested audited model produces its own record rather than bits on its parent`() = onServer {
        deploy(Patient.serializer(), Doctor.serializer())
        val patient = Patient(_id = Patient.ID(id), name = "Ada", ssn = "x", doctor = Doctor(Doctor.ID(otherId), "Dr Who"))

        val disclosures = extract(Patient.serializer(), patient)

        assertEquals(2, disclosures.size, "expected one record each for the patient and the doctor")
        val doctor = disclosures.single { it.model == modelIdOf(Doctor.serializer()) }
        assertEquals(otherId, doctor.recordId)
        assertEquals(setOf("name"), paths(doctor))

        val patientDisclosure = disclosures.single { it.model == modelIdOf(Patient.serializer()) }
        assertEquals(id, patientDisclosure.recordId, "the doctor's id must not be taken for the patient's")
        assertTrue("doctor" in paths(patientDisclosure), "the parent still records that a doctor was disclosed")
    }

    @Test
    fun `every element of a list of audited models gets its own record`() = onServer {
        deploy(Patient.serializer())
        val patients = (1..5).map { Patient(_id = Patient.ID(Uuid.random()), name = "P$it", ssn = "s") }

        val disclosures = extract(ListSerializer(Patient.serializer()), patients)

        assertEquals(5, disclosures.size)
        assertEquals(patients.map { it._id.raw }.toSet(), disclosures.map { it.recordId }.toSet())
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
