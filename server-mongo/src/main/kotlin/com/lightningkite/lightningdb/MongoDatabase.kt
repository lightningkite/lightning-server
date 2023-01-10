package com.lightningkite.lightningdb

import com.github.jershell.kbson.*
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.Settings
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.reactivestreams.client.MongoClient
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import kotlinx.serialization.serializer
import org.bson.BsonTimestamp
import org.bson.UuidRepresentation
import org.bson.types.Binary
import org.bson.types.ObjectId
import org.litote.kmongo.Id
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.id.StringId
import org.litote.kmongo.id.WrappedObjectId
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
import java.util.regex.Pattern
import kotlin.reflect.KClass
import kotlin.reflect.KType

class MongoDatabase(val makeDatabase: () -> CoroutineDatabase) : Database {
    val database by lazy { makeDatabase() }

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
    }

    companion object {
        val bson by lazy { KBson(serializersModule = kmongoSerializationModule, configuration = configuration) }

        init {
            DatabaseSettings.register("mongodb") {
                MongoDatabase {
                    KMongo.createClient(
                        MongoClientSettings.builder()
                            .applyConnectionString(ConnectionString(it.url))
                            .uuidRepresentation(UuidRepresentation.STANDARD)
                            .applyToConnectionPoolSettings {
                                if (Settings.isServerless) {
                                    it.maxSize(4)
                                    it.maxConnectionIdleTime(15, TimeUnit.SECONDS)
                                }
                            }
                            .build()
                    ).coroutine.getDatabase(it.databaseName)
                }
            }
            DatabaseSettings.register("mongodb+srv") {
                MongoDatabase {
                    KMongo.createClient(
                        MongoClientSettings.builder()
                            .applyConnectionString(ConnectionString(it.url))
                            .uuidRepresentation(UuidRepresentation.STANDARD)
                            .applyToConnectionPoolSettings {
                                if (Settings.isServerless) {
                                    it.maxSize(4)
                                    it.maxConnectionIdleTime(15, TimeUnit.SECONDS)
                                }
                            }
                            .build()
                    ).coroutine.getDatabase(it.databaseName)
                }
            }
            DatabaseSettings.register("mongodb-test") {
                MongoDatabase { testMongo().coroutine.getDatabase(it.databaseName) }
            }
            DatabaseSettings.register("mongodb-file") {
                MongoDatabase { embeddedMongo(File(it.url.removePrefix("mongodb-file://"))).coroutine.getDatabase(it.databaseName) }
            }
        }
    }

    private val collections = ConcurrentHashMap<String, Lazy<MongoFieldCollection<*>>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(type: KType, name: String): MongoFieldCollection<T> =
        (collections.getOrPut(name) {
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                MongoFieldCollection(
                    bson.serializersModule.serializer(type) as KSerializer<T>,
                    database
                        .database
                        .getCollection(name, (type.classifier as KClass<*>).java as Class<T>)
                        .coroutine
                )
            }
        } as Lazy<MongoFieldCollection<T>>).value
}
