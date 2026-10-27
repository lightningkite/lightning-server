package com.lightningkite.lightningserver.fixtures

import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.data.References
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.TypedId
import com.lightningkite.services.database.insertOne
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@Serializable
data class Owner(override val _id: ID, val name: String) : HasId<Owner.ID> {
    @Serializable
    @JvmInline
    @References(Owner::class)
    value class ID(override val raw: Uuid) : TypedId<Uuid, ID>
}

@Serializable
data class Pet(override val _id: Uuid, val owner: Owner.ID, val name: String) : HasId<Uuid>

@Serializable
data class Login(override val _id: Uuid = Uuid.random(), val manages: Set<Owner.ID>) : HasId<Uuid>

data class PetConfig(override val seed: Long = 1, override val scale: Double = 1.0) : TestFixtureConfig

object PetServer : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val owners = database.registerTable<Owner>("Owner")
    val pets = database.registerTable<Pet>("Pet")
    val logins = database.registerTable<Login>("Login")

    val seedData = FixtureRegistry<PetConfig>()
    val seededOwners = seedData.seed(owners, "owners") {
        LazyList((config.scale * 5).toInt()) { Owner(Owner.ID(randomUuid()), randomStringFullName()) }
    }
    // Every other owner has a second pet.
    val seededPets = seedData.seed(pets, "pets") {
        seededOwners().expand(2) { owner, index ->
            if (index == 1 && randomBoolean()) null else Pet(randomUuid(), owner._id, randomStringName())
        }
    }
}

class FixturesTest {
    private fun debugServer(action: suspend context(TestRunner<PetServer>) PetServer.() -> Unit) =
        PetServer.testBlocking(settings = {
            database set Database.Settings("ram")
            generalSettings set GeneralServerSettings(debug = true)
        }, action = action)

    @Test
    fun `a seed generates the same values every time`() {
        val sentences = List(2) { with(Faker(42)) { randomStringGarbleSentences(3) } }
        assertEquals(sentences[0], sentences[1])

        val list = with(Faker(42)) { LazyList(10) { randomUuid() }.map { it to randomInt() } }
        assertEquals(list.toList(), list.toList())
        assertEquals(10, list.toSet().size)
    }

    @Test
    fun `fixtures insert their rows after the fixtures they read`() = debugServer {
        execute {
            seedData.execute(PetConfig(scale = 2.0))
            val ownerIds = owners().find(Condition.Always).toList().map { it._id }.toSet()
            val pets = pets().find(Condition.Always).toList()
            assertEquals(10, ownerIds.size)
            assertTrue(pets.size in 10..20)
            assertTrue(pets.all { it.owner in ownerIds })
        }
    }

    @Test
    fun `a registry requiring debug refuses to run without it`() = PetServer.testBlocking(settings = {
        database set Database.Settings("ram")
    }) {
        execute { assertFailsWith<IllegalStateException> { seedData.execute(PetConfig()) } }
    }

    @Test
    fun `two fixtures on one table cannot share a seed`() {
        assertFailsWith<IllegalStateException> {
            object : ServerBuilder() {
                val database = setting("database", Database.Settings())
                val pets = database.registerTable<Pet>("Pet")
                val registry = FixtureRegistry<PetConfig>()
                val a = registry.seed(pets, "same") { emptyList<Pet>() }
                val b = registry.seed(pets, "same") { emptyList<Pet>() }
            }
        }
    }

    @Test
    fun `deleting seed data removes what depended on it and keeps the rest`() = debugServer {
        execute {
            seedData.execute(PetConfig())
            val seedOwner = owners().find(Condition.Always).toList().first()
            val seededCounts = listOf(owners().count(), pets().count())

            val handMadeOwner = owners().insertOne(Owner(Owner.ID(Uuid.random()), "Hand Made"))!!
            val handMadePet = pets().insertOne(Pet(Uuid.random(), handMadeOwner._id, "Kept"))!!
            pets().insertOne(Pet(Uuid.random(), seedOwner._id, "Orphaned"))
            val login = logins().insertOne(Login(manages = setOf(seedOwner._id)))!!

            deleteSeedData(noCascade = setOf(logins))

            assertEquals(listOf(handMadeOwner), owners().find(Condition.Always).toList())
            assertEquals(listOf(handMadePet), pets().find(Condition.Always).toList())
            assertEquals(listOf(login), logins().find(Condition.Always).toList())

            seedData.execute(PetConfig())
            assertEquals(seededCounts.map { it + 1 }, listOf(owners().count(), pets().count()))
        }
    }
}
