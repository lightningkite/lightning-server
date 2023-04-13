package com.lightningkite.lightningdb

import com.github.jershell.kbson.*
import com.lightningkite.lightningserver.core.Disconnectable
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.Settings
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.reactivestreams.client.MongoClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.serializer
import org.bson.BsonTimestamp
import org.bson.UuidRepresentation
import org.bson.types.Binary
import org.bson.types.ObjectId
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import org.litote.kmongo.serialization.*
import org.litote.kmongo.serialization.InstantSerializer
import org.litote.kmongo.serialization.LocalDateSerializer
import org.litote.kmongo.serialization.LocalTimeSerializer
import org.litote.kmongo.serialization.OffsetDateTimeSerializer
import org.litote.kmongo.serialization.ZonedDateTimeSerializer
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
    private var databaseLazy = lazy { client.value.coroutine.getDatabase(databaseName) }
    val database get() = databaseLazy.value
    private var coroutineCollections = ConcurrentHashMap<String, Lazy<CoroutineCollection<*>>>()
    override suspend fun disconnect() {
        if (client.isInitialized()) client.value.close()
        client = lazy(makeClient)
        databaseLazy = lazy { client.value.coroutine.getDatabase(databaseName) }
        coroutineCollections = ConcurrentHashMap<String, Lazy<CoroutineCollection<*>>>()
    }

    override suspend fun connect() {
        // KEEP THIS AROUND.
        // This initializes the database call at startup.
        healthCheck()
    }

    companion object {
        val bson by lazy { KBson(serializersModule = kmongoSerializationModule, configuration = configuration) }

        init {
            registerModule(Serialization.Internal.module.overwriteWith(SerializersModule {
                contextual(Duration::class, DurationMsSerializer)
                contextual(UUID::class, com.github.jershell.kbson.UUIDSerializer)
                contextual(ObjectId::class, ObjectIdSerializer)
                contextual(BigDecimal::class, BigDecimalSerializer)
                contextual(ByteArray::class, ByteArraySerializer)
                contextual(Instant::class, InstantSerializer)
                contextual(ZonedDateTime::class, ZonedDateTimeSerializer)
                contextual(OffsetDateTime::class, OffsetDateTimeSerializer)
                contextual(LocalDate::class, LocalDateSerializer)
                contextual(LocalDateTime::class, LocalDateTimeSerializer)
                contextual(LocalTime::class, LocalTimeSerializer)
                contextual(OffsetTime::class, OffsetTimeSerializer)
                contextual(BsonTimestamp::class, BsonTimestampSerializer)
                contextual(Locale::class, LocaleSerializer)
                contextual(Binary::class, BinarySerializer)
            }))
            DatabaseSettings.register("mongodb") {
                MongoDatabase(databaseName = it.databaseName) {
                    KMongo.createClient(
                        MongoClientSettings.builder()
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
                MongoDatabase(databaseName = it.databaseName) {
                    KMongo.createClient(
                        MongoClientSettings.builder()
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
                MongoDatabase(databaseName = it.databaseName) { testMongo() }
            }
            DatabaseSettings.register("mongodb-file") {
                MongoDatabase(databaseName = it.databaseName) { embeddedMongo(File(it.url.removePrefix("mongodb-file://"))) }
            }
        }
    }

    private val collections = ConcurrentHashMap<String, Lazy<MongoFieldCollection<*>>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(type: KType, name: String): MongoFieldCollection<T> =
        (collections.getOrPut(name) {
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                MongoFieldCollection(
                    bson.serializersModule.serializer(type) as KSerializer<T>
                ) {
                    (coroutineCollections.getOrPut(name) {
                        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                            databaseLazy.value
                                .database
                                .getCollection(name, (type.classifier as KClass<*>).java as Class<T>)
                                .coroutine
                        }
                    } as Lazy<CoroutineCollection<T>>).value
                }
            }
        } as Lazy<MongoFieldCollection<T>>).value
}
