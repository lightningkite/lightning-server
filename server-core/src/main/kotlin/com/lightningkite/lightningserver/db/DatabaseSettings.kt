package com.lightningkite.lightningserver.db

import com.lightningkite.lightningserver.SetOnce
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
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
    val url: String = "ram-unsafe-persist://${File("./local/mongo").absolutePath}",
    val databaseName: String = "default"
): ()->Database {

    companion object: Pluggable<DatabaseSettings, Database>() {
        init {
            register("ram") { InMemoryDatabase() }
            register("ram-preload") { InMemoryDatabase(Serialization.json.parseToJsonElement(File(it.url.substringAfter("://")).readText()) as? JsonObject) }
            register("ram-unsafe-persist") { InMemoryUnsafePersistenceDatabase(File(it.url.substringAfter("://"))) }
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

class InMemoryDatabase(val premadeData: JsonObject? = null): Database {
    val collections = HashMap<String, FieldCollection<*>>()
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(type: KType, name: String): FieldCollection<T>
            = collections.getOrPut(name) {
        val made = InMemoryFieldCollection<T>()
        premadeData?.get(name)?.let {
            val data = Serialization.json.decodeFromJsonElement(ListSerializer(Serialization.json.serializersModule.serializer(type) as KSerializer<T>), it)
            made.data.addAll(data)
        }
        made
    } as FieldCollection<T>
}

class InMemoryUnsafePersistenceDatabase(val folder: File): Database {
    init {
        folder.mkdirs()
    }
    val collections = HashMap<String, FieldCollection<*>>()
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(type: KType, name: String): FieldCollection<T>
            = collections.getOrPut(name) {
        InMemoryUnsafePersistentFieldCollection(Serialization.json, Serialization.json.serializersModule.serializer(type) as KSerializer<T>, folder.resolve(name))
    } as FieldCollection<T>
}