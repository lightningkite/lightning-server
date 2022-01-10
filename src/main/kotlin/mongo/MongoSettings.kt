package mongo

import com.lightningkite.ktorkmongo.embeddedMongo
import com.lightningkite.ktorkmongo.testMongo
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.squareup.okhttp.internal.framed.Settings
import org.bson.UuidRepresentation
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import java.io.File
import java.net.URI

@kotlinx.serialization.Serializable
data class MongoSettings(
    val url: String = "file://${File("./local/mongo").absolutePath}"
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

    companion object {
        var instance: MongoSettings = MongoSettings()
    }
    init { instance = this }
}

val mongo get() = MongoSettings.instance.client