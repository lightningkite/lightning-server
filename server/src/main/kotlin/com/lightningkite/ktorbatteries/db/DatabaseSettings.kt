package com.lightningkite.ktorbatteries.db

import com.lightningkite.ktorbatteries.SetOnce
import com.lightningkite.ktorbatteries.SettingSingleton
import com.lightningkite.ktorbatteries.mongo.mongoDb
import com.lightningkite.ktorbatteries.serialization.Serialization
import com.lightningkite.ktorbatteries.serverhealth.HealthCheckable
import com.lightningkite.ktorbatteries.serverhealth.HealthStatus
import com.lightningkite.ktordb.*
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import org.bson.UuidRepresentation
import org.litote.kmongo.reactivestreams.KMongo
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.KClassifier
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection

var database: Database by SetOnce()


@Serializable
data class DatabaseSettings(
    val url: String = "file-mongodb://${File("./local/mongo").absolutePath}",
    val databaseName: String = "default"
) : HealthCheckable {
    val db by lazy {
        when {
            url == "ram" -> InMemoryDatabase()
            url == "ram-preload" -> InMemoryDatabase(Serialization.json.parseToJsonElement(File(url.substringAfter("://")).readText()) as? JsonObject)
            url == "ram-unsafe-persist" -> InMemoryUnsafePersistenceDatabase(File(url.substringAfter("://")))
            url == "mongodb-test" -> testMongo().database(databaseName)
            url.startsWith("mongodb-file:") -> embeddedMongo(File(url.removePrefix("mongodb-file://"))).database(databaseName)
            url.startsWith("mongodb:") -> KMongo.createClient(
                MongoClientSettings.builder()
                    .applyConnectionString(ConnectionString(url))
                    .uuidRepresentation(UuidRepresentation.STANDARD)
                    .build()
            ).database(databaseName)
            else -> throw IllegalArgumentException("MongoDB connection style not recognized: got $url but only understand file:, mongodb:, and test")
        }
    }

    companion object : SettingSingleton<DatabaseSettings>() {
    }

    init {
        DatabaseSettings.instance = this
        database = db
    }

    override val healthCheckName: String get() = "Database"
    override suspend fun healthCheck(): HealthStatus =
        try {
            withTimeout(5000L) {
                mongoDb.database.listCollectionNames()
                HealthStatus(HealthStatus.Level.OK)
            }
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
}


class InMemoryDatabase(val premadeData: JsonObject? = null): Database {
    val collections = HashMap<String, FieldCollection<*>>()
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(clazz: KClass<T>, name: String): FieldCollection<T>
            = collections.getOrPut(name) {
        val made = InMemoryFieldCollection<T>()
        premadeData?.get(name)?.let {
            val data = Serialization.json.decodeFromJsonElement(ListSerializer(Serialization.json.serializersModule.serializer(object: KType {
                override val annotations: List<Annotation>
                    get() = listOf()
                override val arguments: List<KTypeProjection>
                    get() = listOf()
                override val classifier: KClassifier
                    get() = clazz
                override val isMarkedNullable: Boolean
                    get() = false
            }) as KSerializer<T>), it)
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
    override fun <T : Any> collection(clazz: KClass<T>, name: String): FieldCollection<T>
            = collections.getOrPut(name) {
        InMemoryUnsafePersistentFieldCollection(Serialization.json, Serialization.json.serializersModule.serializer(object: KType {
            override val annotations: List<Annotation>
                get() = listOf()
            override val arguments: List<KTypeProjection>
                get() = listOf()
            override val classifier: KClassifier
                get() = clazz
            override val isMarkedNullable: Boolean
                get() = false
        }) as KSerializer<T>, folder.resolve(name))
    } as FieldCollection<T>
}