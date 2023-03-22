package com.lightningkite.lightningserver.db

import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.settings.Pluggable
import com.lightningkite.lightningserver.settings.setting
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KClassifier
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection

/**
 * Settings that define what database to use and how to connect to it.
 *
 * @param url Defines the type and connection to the database. examples are ram, ram-preload, ram-unsafe-persist, mongodb-test, mongodb-file, mongodb
 * @param databaseName The name of the database to connect to.
 */
@Serializable
data class DatabaseSettings(
    val url: String = "ram-unsafe-persist://${File("./local/database").absolutePath}",
    val databaseName: String = "default",
) : () -> Database {

    companion object : Pluggable<DatabaseSettings, Database>() {
        init {
            register("ram") { InMemoryDatabase() }
            register("ram-preload") {
                InMemoryDatabase(
                    Serialization.Internal.json.parseToJsonElement(
                        File(it.url.substringAfter("://"))
                            .readText()
                    ) as? JsonObject
                )
            }
            register("ram-unsafe-persist") { InMemoryUnsafePersistenceDatabase(File(it.url.substringAfter("://"))) }
            register("delay") {
                val x = it.url.substringAfter("://")
                val delay = x.substringBefore("/").toLong()
                val wraps = x.substringAfter("/")
                parse(wraps, DatabaseSettings(wraps, it.databaseName)).delayed(delay)
            }
        }
    }

    override fun invoke(): Database = parse(url.substringBefore("://"), this)

//    override fun invoke(): Database = when {
//        url == "ram" -> InMemoryDatabase()
//        url == "ram-preload" -> InMemoryDatabase(Serialization.json.parseToJsonElement(File(url.substringAfter("://")).readText()) as? JsonObject)
//        url == "ram-unsafe-persist" -> InMemoryUnsafePersistenceDatabase(File(url.substringAfter("://")))
//        url == "mongodb-test" -> testMongo().database(databaseName)
//        url.startsWith("mongodb-file:") -> embeddedMongo(File(url.removePrefix("mongodb-file://"))).database(databaseName)
//        url.startsWith("mongodb:") -> KMongo.createClient(
//            MongoClientSettings.builder()
//                .applyConnectionString(ConnectionString(url))
//                .uuidRepresentation(UuidRepresentation.STANDARD)
//                .build()
//        ).database(databaseName)
//        else -> throw IllegalArgumentException("MongoDB connection style not recognized: got $url but only understand: " +
//                "ram\n" +
//                "ram-preload\n" +
//                "ram-unsafe-persist\n" +
//                "mongodb-test\n" +
//                "mongodb-file:\n" +
//                "mongodb:"
//        )
//    }
}

class InMemoryDatabase(val premadeData: JsonObject? = null) : Database {
    val collections = HashMap<String, FieldCollection<*>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(type: KType, name: String): FieldCollection<T> = collections.getOrPut(name) {
        val made = InMemoryFieldCollection<T>()
        premadeData?.get(name)?.let {
            val data = Serialization.Internal.json.decodeFromJsonElement(
                ListSerializer(
                    Serialization.Internal.json.serializersModule.serializer(type) as KSerializer<T>
                ), it
            )
            made.data.addAll(data)
        }
        made
    } as FieldCollection<T>

    fun drop() {
        collections.forEach {
            (it.value as InMemoryFieldCollection).data.clear()
        }
    }
}

class InMemoryUnsafePersistenceDatabase(val folder: File) : Database {
    init {
        folder.mkdirs()
    }

    val collections = HashMap<String, FieldCollection<*>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(type: KType, name: String): FieldCollection<T> = synchronized(collections) {
        collections.getOrPut(name) {
            val fileName = name.filter { it.isLetterOrDigit() }
            val oldStyle = folder.resolve(fileName)
            val storage = folder.resolve("$fileName.json")
            if (oldStyle.exists() && !storage.exists())
                oldStyle.copyTo(storage, overwrite = true)
            InMemoryUnsafePersistentFieldCollection(
                Serialization.Internal.json,
                Serialization.Internal.json.serializersModule.serializer(type) as KSerializer<T>,
                storage
            )
        } as FieldCollection<T>
    }


    fun drop() {
        collections.forEach {
            (it.value as InMemoryFieldCollection).data.clear()
        }
    }
}