@file:UseContextualSerialization(UUID::class)
package com.lightningkite.lightningdb

import com.lightningkite.lightningdb.test.*
import com.lightningkite.prepareModelsServerCore
import com.lightningkite.lightningdb.test.prepareModelsServerTesting
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseContextualSerialization
import org.junit.Test
import java.util.*
import com.lightningkite.UUID
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.logging.LoggingSettings
import com.lightningkite.lightningserver.logging.loggingSettings
import com.lightningkite.lightningserver.settings.Settings
import com.lightningkite.prepareModelsShared
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.lightningkite.uuid
import com.mongodb.kotlin.client.coroutine.MongoClient
import kotlinx.coroutines.delay
import org.junit.AfterClass
import org.junit.BeforeClass
import java.io.File
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class SearchTextConditionTest {

    @Serializable
    @GenerateDataClassPaths
    @TextIndex(["string", "otherField", "anotherField"])
    data class ModelWithTextIndex2(
        override val _id: UUID = uuid(),
        val string: String = "nothere",
        val otherField: String? = null,
        val anotherField: Int = 201,
    ): HasId<UUID>

    companion object {
        var db: MongoDatabase? = null

        @BeforeClass
        @JvmStatic
        fun start() {
            Settings.clear()
            Settings.populateDefaults(mapOf(loggingSettings.name to LoggingSettings(default = LoggingSettings.ContextSettings(
                level = "VERBOSE",
                toConsole = true,
            ))))

            MongoDatabase
            prepareModelsShared()
            prepareModelsServerCore()
            prepareModelsServerTesting()
            prepareModelsServerMongoTest()
            val urlFile = File("local/mongodb-atlas-url.txt")
            println("urlFile ${urlFile.absolutePath} has ${urlFile.takeIf { it.exists() }?.readText()}")
            val url = urlFile.takeIf { it.exists() } ?: run {
                println("No file found at ${urlFile.absolutePath}")
                return
            }
            db = DatabaseSettings(url.readText())() as MongoDatabase
            runBlocking {
                val defaultMongo = db ?: return@runBlocking
                val collection = defaultMongo.collection<ModelWithTextIndex2>() as MongoFieldCollection<ModelWithTextIndex2>
//                collection.deleteMany(Condition.Always)
//                collection.insertMany(listOf(
//                    "Alfa",
//                    "Bravo",
//                    "Charlie",
//                    "Delta",
//                    "Echo",
//                    "Foxtrot",
//                    "Golf",
//                    "Hotel",
//                    "India",
//                    "Juliett",
//                    "Kilo",
//                    "Lima",
//                    "Mike",
//                    "November",
//                    "Oscar",
//                    "Papa",
//                    "Quebec",
//                    "Romeo",
//                    "Sierra",
//                    "Tango",
//                    "Uniform",
//                    "Victor",
//                    "Whiskey",
//                    "Xray",
//                    "Yankee",
//                    "Zulu",
//                ).zipWithNext { a, b -> ModelWithTextIndex2(string = "$a $b", otherField = "sample") })
            }
        }

        @AfterClass
        @JvmStatic
        fun after() {
            runBlocking { db?.disconnect() }
        }
    }

    @Test
    fun testTextSearch() = runBlocking {
        val defaultMongo = db ?: return@runBlocking
        val collection = defaultMongo.collection<ModelWithTextIndex2>() as MongoFieldCollection<ModelWithTextIndex2>

        collection
            .find(condition { it.fullTextSearch("hotel india", requireAllTermsPresent = true) })
            .toList()
            .let { assertEquals(1, it.size, "Got $it") }

//        var query = "One"
//        var condition = path<ModelWithTextIndex2>().fullTextSearch(query, requireAllTermsPresent = true)
//        var results = collection.find(condition).toList()
//        assertContains(results, value1)
//
//        query = "One two three"
//        condition = path<ModelWithTextIndex2>().fullTextSearch(query, requireAllTermsPresent = true)
//        results = collection.find(condition).toList()
//        assertContains(results, value1)
//
//        query = "One two four"
//        condition = path<ModelWithTextIndex2>().fullTextSearch(query, requireAllTermsPresent = true)
//        results = collection.find(condition).toList()
//        assertTrue(results.isEmpty())
//
//        query = "one"
//        condition = path<ModelWithTextIndex2>().fullTextSearch(query, requireAllTermsPresent = true)
//        results = collection.find(condition).toList()
//        assertContains(results, value1)
    }

}