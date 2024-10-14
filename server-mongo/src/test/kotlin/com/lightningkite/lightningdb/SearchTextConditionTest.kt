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
import kotlin.time.Duration.Companion.seconds

class SearchTextConditionTest {

    @Serializable
    @GenerateDataClassPaths
    @TextIndex(["string", "otherField", "anotherField"], 0.1)
    data class ModelWithTextIndex2(
        override val _id: UUID = uuid(),
        val string: String = "nothere",
        val otherField: String? = null,
        val anotherField: Int = 201,
    ): HasId<UUID>

    companion object {
        var db: MongoDatabase? = null

        val value1 = ModelWithTextIndex2(_id = UUID(0UL, 1UL), string = "One Two Three", otherField = "alpha")
        val value2 = ModelWithTextIndex2(_id = UUID(0UL, 2UL), string = "Five Six Seven", otherField = "beta")

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
//                collection.insertMany(listOf(value1, value2))
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

        delay(1.seconds)

        var query = "One"
        var condition = path<ModelWithTextIndex2>().fullTextSearch(query, false)
        var results = collection.find(condition).toList()
        assertContains(results, value1)

        // TODO: Respect caps sensitivity?
//        query = "one"
//        condition = path<ModelWithTextIndex2>().fullTextSearch(query, false)
//        results = collection.find(condition).toList()
//        assertFalse(results.contains(value1))

        query = "one"
        condition = path<ModelWithTextIndex2>().fullTextSearch(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value1)

        query = "four two"
        condition = path<ModelWithTextIndex2>().fullTextSearch(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value1)

        query = "four five"
        condition = path<ModelWithTextIndex2>().fullTextSearch(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value2)

        query = "three five"
        condition = path<ModelWithTextIndex2>().fullTextSearch(query, true)
        results = collection.find(condition).toList()
        assertContains(results, value1)
        assertContains(results, value2)
    }

    @Test
    fun testTextSearchMisspell() = runBlocking {
        val defaultMongo = db ?: return@runBlocking
        val collection = defaultMongo.collection<ModelWithTextIndex2>() as MongoFieldCollection<ModelWithTextIndex2>

        println("(Threee, true)" + collection.find(path<ModelWithTextIndex2>().fullTextSearch("Threee", true)).toList())
        println("(Threee, true, 0.5)" + collection.find(path<ModelWithTextIndex2>().fullTextSearch("Threee", true, 0.5)).toList())
        println("(One two three, true, 0.5)" + collection.find(path<ModelWithTextIndex2>().fullTextSearch("One two three", true, 0.5)).toList())
        println("(three five, true, 0.25)" + collection.find(path<ModelWithTextIndex2>().fullTextSearch("three five", true, 0.25)).toList())
        println("(three five, true, 0.5)" + collection.find(path<ModelWithTextIndex2>().fullTextSearch("three five", true, 0.5)).toList())
    }

}