package com.lightningkite.lightningserver.mongo

import com.github.jershell.kbson.UUIDSerializer
import com.lightningkite.lightningserver.SettingSingleton
import com.lightningkite.lightningserver.db.database
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningdb.database
import com.lightningkite.lightningdb.embeddedMongo
import com.lightningkite.lightningdb.registerRequiredSerializers
import com.lightningkite.lightningdb.testMongo
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import kotlinx.coroutines.withTimeout
import org.bson.UuidRepresentation
import org.litote.kmongo.reactivestreams.KMongo
import java.io.File

@Deprecated(
    "Use DatabaseSettings instead.",
    replaceWith = ReplaceWith("DatabaseSettings", "com.lightningkite.lightningserver.db.DatabaseSettings")
)
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
            registerRequiredSerializers()
        }
    }

    init {
        instance = this
        database = MongoSettings.instance.client.database(MongoSettings.instance.databaseName)
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

@Deprecated("Use database instead", ReplaceWith("database", "com.lightningkite.lightningserver.db"))
val mongoDb get() = MongoSettings.instance.client.database(MongoSettings.instance.databaseName)
