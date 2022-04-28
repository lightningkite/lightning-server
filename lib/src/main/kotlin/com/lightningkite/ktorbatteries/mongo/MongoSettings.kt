package com.lightningkite.ktorbatteries.mongo

import com.lightningkite.kotlinercli.cli
import com.lightningkite.ktorbatteries.SettingSingleton
import com.lightningkite.ktorbatteries.serverhealth.HealthCheckable
import com.lightningkite.ktorbatteries.serverhealth.HealthStatus
import com.lightningkite.ktorkmongo.database
import com.lightningkite.ktorkmongo.embeddedMongo
import com.lightningkite.ktorkmongo.fixUuidSerialization
import com.lightningkite.ktorkmongo.testMongo
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import kotlinx.coroutines.withTimeout
import org.bson.Document
import org.bson.UuidRepresentation
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.coroutine.toList
import org.litote.kmongo.reactivestreams.KMongo
import java.io.File

@kotlinx.serialization.Serializable
data class MongoSettings(
    val url: String = "file://${File("./local/mongo").absolutePath}",
    val databaseName: String = "default"
) : HealthCheckable {
    val client by lazy {
        when {
            url == "test" -> testMongo()
            url.startsWith("file:") -> embeddedMongo(File(url.removePrefix("file://")))
            url.startsWith("mongodb:") -> KMongo.createClient(
                MongoClientSettings.builder()
                    .applyConnectionString(ConnectionString(url))
                    .uuidRepresentation(UuidRepresentation.STANDARD)
                    .build()
            )
            else -> throw IllegalArgumentException("MongoDB connection style not recognized: got $url but only understand file:, mongodb:, and test")
        }
    }

    companion object : SettingSingleton<MongoSettings>() {
        init {
            fixUuidSerialization()
        }
    }

    init {
        instance = this
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

val mongoDb get() = MongoSettings.instance.client.database(MongoSettings.instance.databaseName)
