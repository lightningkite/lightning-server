package com.lightningkite.lightningserver.typed.denormalize

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.executeStartupTasks
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.settings.setStatic
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.modelInfo
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.data.Denormalized
import com.lightningkite.services.data.Queryable
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.toList
import kotlin.test.*
import kotlin.uuid.Uuid

class DenormalizationTest {

    object DenoServer : ServerBuilder() {
        init { registerBasicMediaTypeCoders() }

        val database = setting("database", Database.Settings())

        val countryInfo = database.modelInfo<HasId<*>?, DenoCountry, Uuid>(
            auth = noAuth,
            tableName = "DenoCountry",
            permissions = { ModelPermissions.allowAll() },
        )

        val cityInfo = database.modelInfoWithDeno<HasId<*>?, DenoCity, Uuid>(
            auth = noAuth,
            tableName = "DenoCity",
            permissions = { ModelPermissions.allowAll() },
        ) { city ->
            city.country.denormalizes(countryInfo) { country ->
                city.countryName bind country.name
                city.countryNameAtCreation set country.name
            }
        }

        val personInfo = database.modelInfoWithDeno<HasId<*>?, DenoPerson, Uuid>(
            auth = noAuth,
            tableName = "DenoPerson",
            permissions = {
                ModelPermissions.allowAll<DenoPerson>().withAdditionalUpdateRestrictions { person ->
                    person.country.cannotBeModified()
                    person.countryName.cannotBeModified()
                    person.favoriteCityName.cannotBeModified()
                }
            },
        ) { person ->
            person.city.denormalizes(cityInfo) { city ->
                person.country bind city.country
            }
            person.country.denormalizes(countryInfo) { country ->
                person.countryName bind country.name
            }
            person.favoriteCity.denormalizes(cityInfo) { city ->
                person.favoriteCityName bind city.name
            }
        }

        val personRefresh = path.path("person-deno") include RefreshDenoEndpoints(personInfo)
        val personRest = path.path("people") include ModelRestEndpoints(personInfo)
    }

    object MisconfiguredServer : ServerBuilder() {
        val database = setting("database", Database.Settings())

        val readsACopy = database.modelInfoWithDeno<HasId<*>?, DenoPerson, Uuid>(
            auth = noAuth,
            tableName = "ReadsACopyPerson",
            permissions = { ModelPermissions.allowAll() },
        ) { person ->
            person.city.denormalizes(DenoServer.cityInfo) { city ->
                person.countryName bind city.countryName
            }
        }

        val writesAnUnmarkedField = database.modelInfoWithDeno<HasId<*>?, DenoCity, Uuid>(
            auth = noAuth,
            tableName = "UnmarkedCity",
            permissions = { ModelPermissions.allowAll() },
        ) { city ->
            city.country.denormalizes(DenoServer.countryInfo) { country ->
                city.name bind country.name
            }
        }
    }

    private fun denoTest(action: suspend context(TestRunner<DenoServer>) DenoServer.() -> Unit) =
        DenoServer.testBlocking(settings = {
            generalSettings set GeneralServerSettings()
            database set Database.Settings()
        }) {
            executeStartupTasks()
            action()
        }

    @Test
    fun create_copies_referenced_fields() = denoTest {
        val france = DenoCountry(name = "France")
        val paris = DenoCity(country = france._id, name = "Paris")
        val person = DenoPerson(city = paris._id)
        execute {
            countryInfo.table().insertOne(france)
            cityInfo.table().insertOne(paris)
            personInfo.table().insertOne(person)
        }

        execute {
            val storedCity = cityInfo.table().get(paris._id)!!
            assertEquals("France", storedCity.countryName)
            assertEquals("France", storedCity.countryNameAtCreation)
            assertEquals("France", personInfo.table().get(person._id)!!.countryName)
        }
    }

    @Test
    fun bind_follows_the_referenced_model_and_set_does_not() = denoTest {
        val france = DenoCountry(name = "France")
        val paris = DenoCity(country = france._id, name = "Paris")
        execute {
            countryInfo.table().insertOne(france)
            cityInfo.table().insertOne(paris)
            countryInfo.table().updateOneById(france._id, Modification { it.name assign "République française" })
        }

        execute {
            val storedCity = cityInfo.table().get(paris._id)!!
            assertEquals("République française", storedCity.countryName)
            assertEquals("France", storedCity.countryNameAtCreation)
        }
    }

    /** Person.countryName comes from Country.name through Person.country, which is denormalized from City.country. */
    @Test
    fun stored_intermediate_reference_follows_the_source() = denoTest {
        val france = DenoCountry(name = "France")
        val paris = DenoCity(country = france._id, name = "Paris")
        val person = DenoPerson(city = paris._id)
        execute {
            countryInfo.table().insertOne(france)
            cityInfo.table().insertOne(paris)
            personInfo.table().insertOne(person)
            countryInfo.table().updateOneById(france._id, Modification { it.name assign "République française" })
        }

        execute {
            assertEquals("République française", personInfo.table().get(person._id)!!.countryName)
        }
    }

    @Test
    fun moving_the_intermediate_reference_recalculates_downstream_fields() = denoTest {
        val france = DenoCountry(name = "France")
        val spain = DenoCountry(name = "Spain")
        val border = DenoCity(country = france._id, name = "Hendaye")
        val person = DenoPerson(city = border._id)
        execute {
            countryInfo.table().insertMany(listOf(france, spain))
            cityInfo.table().insertOne(border)
            personInfo.table().insertOne(person)
            cityInfo.table().updateOneById(border._id, Modification { it.country assign spain._id })
        }

        execute {
            val stored = personInfo.table().get(person._id)!!
            assertEquals(spain._id, stored.country)
            assertEquals("Spain", stored.countryName)
        }
    }

    @Test
    fun client_reference_change_updates_restricted_denormalized_fields() = denoTest {
        val france = DenoCountry(name = "France")
        val spain = DenoCountry(name = "Spain")
        val paris = DenoCity(country = france._id, name = "Paris")
        val madrid = DenoCity(country = spain._id, name = "Madrid")
        val person = DenoPerson(city = paris._id)
        execute {
            countryInfo.table().insertMany(listOf(france, spain))
            cityInfo.table().insertMany(listOf(paris, madrid))
            personInfo.table().insertOne(person)
        }

        val updated = personRest.modify.test(person._id, null, Modification { it.city assign madrid._id })

        assertEquals("Spain", updated.countryName)
    }

    @Test
    fun clients_cannot_write_restricted_denormalized_fields() = denoTest {
        val france = DenoCountry(name = "France")
        val paris = DenoCity(country = france._id, name = "Paris")
        val person = DenoPerson(city = paris._id)
        execute {
            countryInfo.table().insertOne(france)
            cityInfo.table().insertOne(paris)
            personInfo.table().insertOne(person)
        }

        assertFailsWith<NotFoundException> {
            personRest.modify.test(person._id, null, Modification { it.countryName assign "forged" })
        }
        execute { assertEquals("France", personInfo.table().get(person._id)!!.countryName) }
    }

    @Test
    fun reading_a_denormalized_field_fails_when_built() {
        val error = assertFailsWith<IllegalArgumentException> { MisconfiguredServer.readsACopy.denormalizations }
        assertContains(error.message!!, "'countryName' is itself @Denormalized")
    }

    @Test
    fun reading_a_denormalized_field_fails_when_run() {
        val error = assertFailsWith<IllegalArgumentException> {
            MisconfiguredServer.testBlocking({
                database set Database.Settings()
            }) {
                execute {
                    readsACopy.table().all().toList()
                }
            }
        }
        assertContains(error.message!!, "'countryName' is itself @Denormalized")
    }

    @Test
    fun writing_an_unmarked_field_fails_when_built() {
        val error = assertFailsWith<IllegalArgumentException> { MisconfiguredServer.writesAnUnmarkedField.denormalizations }
        assertContains(error.message!!, "must be marked @Denormalized")
    }

    @Test
    fun changing_the_reference_recalculates() = denoTest {
        val france = DenoCountry(name = "France")
        val spain = DenoCountry(name = "Spain")
        val paris = DenoCity(country = france._id, name = "Paris")
        val madrid = DenoCity(country = spain._id, name = "Madrid")
        val person = DenoPerson(city = paris._id)
        execute {
            countryInfo.table().insert(listOf(france, spain))
            cityInfo.table().insert(listOf(paris, madrid))
            personInfo.table().insertOne(person)
            personInfo.table().updateOneById(person._id, Modification { it.city assign madrid._id })
        }

        execute {
            assertEquals("Spain", personInfo.table().get(person._id)!!.countryName)
        }
    }

    @Test
    fun missing_reference_is_rejected() = denoTest {
        assertFailsWith<BadRequestException> {
            execute { cityInfo.table().insertOne(DenoCity(country = Uuid.random(), name = "Atlantis")) }
        }
    }

    @Test
    fun optional_reference_follows_the_referenced_model_and_clears_on_delete() = denoTest {
        val france = DenoCountry(name = "France")
        val paris = DenoCity(country = france._id, name = "Paris")
        val home = DenoCity(country = france._id, name = "Lyon")
        val person = DenoPerson(city = home._id)
        execute {
            countryInfo.table().insertOne(france)
            cityInfo.table().insertMany(listOf(paris, home))
            personInfo.table().insertOne(person)
            assertNull(personInfo.table().get(person._id)!!.favoriteCityName)

            personInfo.table().updateOneById(person._id, Modification { it.favoriteCity assign paris._id })
            assertEquals("Paris", personInfo.table().get(person._id)!!.favoriteCityName)

            cityInfo.table().updateOneById(paris._id, Modification { it.name assign "Paname" })
            assertEquals("Paname", personInfo.table().get(person._id)!!.favoriteCityName)

            cityInfo.table().deleteOneById(paris._id)
            val stored = personInfo.table().get(person._id)!!
            assertNull(stored.favoriteCity)
            assertNull(stored.favoriteCityName)
        }
    }

    @Test
    fun refresh_repairs_stale_values() = denoTest {
        val france = DenoCountry(name = "France")
        val paris = DenoCity(country = france._id, name = "Paris")
        val person = DenoPerson(city = paris._id)
        execute {
            countryInfo.table().insertOne(france)
            cityInfo.table().insertOne(paris)
            personInfo.table().insertOne(person)
            personInfo.baseTable().updateOneById(person._id, Modification { it.countryName assign "stale" })

            val applied = personRefresh.refreshAllDenormalizations()

            assertEquals(setOf(person._id.toString()), applied.keys)
            assertEquals("France", personInfo.table().get(person._id)!!.countryName)
        }
    }
}

@Queryable
data class DenoCountry(
    override val _id: Uuid = Uuid.random(),
    val name: String = "",
) : HasId<Uuid>

@Queryable
data class DenoCity(
    override val _id: Uuid = Uuid.random(),
    val country: Uuid,
    val name: String = "",
    @Denormalized val countryName: String = "",
    @Denormalized val countryNameAtCreation: String = "",
) : HasId<Uuid>

@Queryable
data class DenoPerson(
    override val _id: Uuid = Uuid.random(),
    val city: Uuid,
    @Denormalized val country: Uuid = Uuid.NIL,
    @Denormalized val countryName: String = "",
    val favoriteCity: Uuid? = null,
    @Denormalized val favoriteCityName: String? = null,
) : HasId<Uuid>
