package com.lightningkite.lightningdb

import com.lightningkite.lightningserver.core.Disconnectable
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.Settings
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.bson.BsonDocument
import org.bson.UuidRepresentation
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.reflect.KType

class MongoDatabase(val databaseName: String, private val makeClient: () -> MongoClient) : Database, Disconnectable {

    // You might be asking, "WHY?  WHY IS THIS SO COMPLICATED?"
    // Well, we have to be able to fully disconnect and reconnect exising Mongo databases in order to support AWS's
    // SnapStart feature effectively.  As such, we have to destroy and reproduce all the connections on demand.
    private var client = lazy(makeClient)
    private var databaseLazy = lazy { client.value.getDatabase(databaseName) }
    val database get() = databaseLazy.value
    private var coroutineCollections = ConcurrentHashMap<Pair<KType, String>, Lazy<MongoCollection<BsonDocument>>>()
    override suspend fun disconnect() {
        disconnectImmediate()
    }
    fun disconnectImmediate() {
        if (client.isInitialized()) client.value.close()
        client = lazy(makeClient)
        databaseLazy = lazy { client.value.getDatabase(databaseName) }
        coroutineCollections = ConcurrentHashMap()
    }

    override suspend fun connect() {
        // KEEP THIS AROUND.
        // This initializes the database call at startup.
        healthCheck()
    }

    companion object {
        init {
            DatabaseSettings.register("mongodb") {
                Regex("""mongodb://.*/(?<databaseName>[^?]+)(?:\?.*)?""")
                    .matchEntire(it.url)
                    ?.let { match ->
                        MongoDatabase(databaseName = match.groups["databaseName"]!!.value) {
                            MongoClient.create(MongoClientSettings.builder()
                                .applyConnectionString(ConnectionString(it.url))
                                .uuidRepresentation(UuidRepresentation.STANDARD)
                                .applyToConnectionPoolSettings {
                                    if (Settings.isServerless) {
                                        it.maxSize(4)
                                        it.maxConnectionIdleTime(15, TimeUnit.SECONDS)
                                        it.maxConnectionLifeTime(1L, TimeUnit.MINUTES)
                                    }
                                }
                                .build()
                            )
                        }
                    }
                    ?: throw IllegalStateException("Invalid mongodb URL. The URL should match the pattern: mongodb://[credentials and host information]/[databaseName]?[params]")
            }
            DatabaseSettings.register("mongodb+srv") {
                Regex("""mongodb\+srv://.*/(?<databaseName>[^?]+)(?:\?.*)?""")
                    .matchEntire(it.url)
                    ?.let { match ->
                        MongoDatabase(databaseName = match.groups["databaseName"]!!.value) {
                            MongoClient.create(MongoClientSettings.builder()
                                .applyConnectionString(ConnectionString(it.url))
                                .uuidRepresentation(UuidRepresentation.STANDARD)
                                .applyToConnectionPoolSettings {
                                    if (Settings.isServerless) {
                                        it.maxSize(4)
                                        it.maxConnectionIdleTime(15, TimeUnit.SECONDS)
                                        it.maxConnectionLifeTime(1L, TimeUnit.MINUTES)
                                    }
                                }
                                .build()
                            )
                        }
                    }
                    ?: throw IllegalStateException("Invalid mongodb URL. The URL should match the pattern: mongodb+srv://[credentials and host information]/[databaseName]?[params]")
            }
            DatabaseSettings.register("mongodb-test") {
                Regex("""mongodb-test(?:://(?:\?(?<params>.*))?)?""")
                    .matchEntire(it.url)
                    ?.let { match ->
                        val params: Map<String, List<String>>? = match.groups["params"]?.value?.let { params ->
                            DatabaseSettings.parseParameterString(params)
                        }
                        MongoDatabase(databaseName = "default") {
                            testMongo(version = params?.get("mongoVersion")?.firstOrNull())
                        }
                    }
                    ?: throw IllegalStateException("Invalid mongodb-test URL. The URL should match the pattern: mongodb-test://?[params]\nAvailable params are: mongoVersion")
            }
            DatabaseSettings.register("mongodb-file") {
                Regex("""mongodb-file://(?<folder>[^?]+)(?:\?(?<params>.*))?""")
                    .matchEntire(it.url)
                    ?.let { match ->
                        val folder = match.groups["folder"]!!.value
                        val params: Map<String, List<String>>? = match.groups["params"]?.value?.let { params ->
                            DatabaseSettings.parseParameterString(params)
                        }
                        MongoDatabase(databaseName = params?.get("databaseName")?.firstOrNull() ?: "default") {
                            embeddedMongo(
                                replFile = File(folder),
                                port = params?.get("port")?.firstOrNull()?.toIntOrNull(),
                                version = params?.get("mongoVersion")?.firstOrNull()
                            )
                        }

                    }
                    ?: throw IllegalStateException("Invalid mongodb-file URL. The URL should match the pattern: mongodb-file://[FolderPath]?[params]\nAvailable params are: mongoVersion, port, databaseName")
            }
        }
    }

    private val collections = ConcurrentHashMap<Pair<KType, String>, Lazy<MongoFieldCollection<*>>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(type: KType, name: String): MongoFieldCollection<T> =
        (collections.getOrPut(type to name) {
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                MongoFieldCollection(
                    Serialization.Internal.bson.serializersModule.serializer(type) as KSerializer<T>,
                    getMongo = {
                        (coroutineCollections.getOrPut(type to name) {
                            lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                                databaseLazy.value
                                    .getCollection(name, BsonDocument::class.java)
                            }
                        } as Lazy<MongoCollection<BsonDocument>>).value
                    },
                    onConnectionError = {
                        disconnectImmediate()
                    }
                )
            }
        } as Lazy<MongoFieldCollection<T>>).value
}
