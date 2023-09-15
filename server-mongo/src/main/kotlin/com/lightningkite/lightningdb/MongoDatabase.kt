package com.lightningkite.lightningdb

import com.github.jershell.kbson.*
import com.lightningkite.lightningserver.core.Disconnectable
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.Settings
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.serializer
import org.bson.BsonDocument
import org.bson.BsonTimestamp
import org.bson.UuidRepresentation
import org.bson.types.Binary
import org.bson.types.ObjectId
import java.io.File
import java.math.BigDecimal
import java.time.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.reflect.KType

class MongoDatabase(val databaseName: String, private val makeClient: () -> MongoClient) : Database, Disconnectable {

    // You might be asking, "WHY?  WHY IS THIS SO COMPLICATED?"
    // Well, we have to be able to fully disconnect and reconnect exising Mongo databases in order to support AWS's
    // SnapStart feature effectively.  As such, we have to destroy and reproduce all the connections on demand.
    private var client = lazy(makeClient)
    private var databaseLazy = lazy { client.value.getDatabase(databaseName) }
    val database get() = databaseLazy.value
    private var coroutineCollections = ConcurrentHashMap<Pair<KSerializer<*>, String>, Lazy<MongoCollection<BsonDocument>>>()
    override suspend fun disconnect() {
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
                val databaseName: String =
                    it.url.substringAfter("://").substringAfter('@').substringAfter('/', "").substringBefore('?')
                MongoDatabase(databaseName = databaseName) {
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
            DatabaseSettings.register("mongodb+srv") {
                val databaseName: String =
                    it.url.substringAfter("://").substringAfter('@').substringAfter('/', "").substringBefore('?')
                MongoDatabase(databaseName = databaseName) {
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
            DatabaseSettings.register("mongodb-test") {
                MongoDatabase(databaseName = "default") { testMongo() }
            }
            DatabaseSettings.register("mongodb-file") {
                MongoDatabase(databaseName = "default") { embeddedMongo(File(it.url.removePrefix("mongodb-file://"))) }
            }
        }
    }

    private val collections = ConcurrentHashMap<Pair<KSerializer<*>, String>, Lazy<MongoFieldCollection<*>>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(serializer: KSerializer<T>, name: String): MongoFieldCollection<T> =
        (collections.getOrPut(serializer to name) {
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                MongoFieldCollection(serializer) {
                    (coroutineCollections.getOrPut(serializer to name) {
                        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                            databaseLazy.value
                                .getCollection(name, BsonDocument::class.java)
                        }
                    } as Lazy<MongoCollection<BsonDocument>>).value
                }
            }
        } as Lazy<MongoFieldCollection<T>>).value
}
