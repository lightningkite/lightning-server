package com.lightningkite.ktorbatteries.mongo

import com.lightningkite.ktorbatteries.SettingSingleton
import com.lightningkite.ktorkmongo.fixUuidSerialization
import com.lightningkite.ktorkmongo.testMongo
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import org.bson.UuidRepresentation
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import java.io.File

@kotlinx.serialization.Serializable
data class MongoSettings(
    val url: String = "file://${File("./local/mongo").absolutePath}",
    val databaseName: String = "default"
) {
    val client by lazy {
        when {
            url == "test" -> testMongo()
            url.startsWith("file:") -> embeddedMongo(File(url.removePrefix("file://")))
            url.startsWith("mongodb:") -> KMongo.createClient(
                MongoClientSettings.builder()
                    .applyConnectionString(ConnectionString(url))
                    .uuidRepresentation(UuidRepresentation.STANDARD)
                    .build()
            ).coroutine
            else -> throw IllegalArgumentException("MongoDB connection style not recognized: got $url but only understand file:, mongodb:, and test")
        }
    }

    companion object: SettingSingleton<MongoSettings>() {
        init { fixUuidSerialization() }
    }
    init {
        instance = this
    }
}

val mongoDb get() = MongoSettings.instance.client.getDatabase(MongoSettings.instance.databaseName)
