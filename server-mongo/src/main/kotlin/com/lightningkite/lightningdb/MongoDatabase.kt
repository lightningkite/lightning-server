package com.lightningkite.lightningdb

import com.lightningkite.lightningserver.core.Disconnectable
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.settings.Settings
import com.mongodb.*
import com.mongodb.event.ConnectionCheckOutStartedEvent
import com.mongodb.event.ConnectionCheckedInEvent
import com.mongodb.event.ConnectionCheckedOutEvent
import com.mongodb.event.ConnectionPoolListener
import com.mongodb.kotlin.client.coroutine.MongoClient
import com.mongodb.kotlin.client.coroutine.MongoCollection
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.bson.BsonDocument
import org.bson.UuidRepresentation
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.reflect.KType

class MongoDatabase(val databaseName: String, val atlasSearch: Boolean = false, val clientSettings: MongoClientSettings) : Database, Disconnectable {

    companion object {
        init {
            DatabaseSettings.register("mongodb") {
                Regex("""mongodb://.*/(?<databaseName>[^?]+)(?:\?.*)?""")
                    .matchEntire(it.url)
                    ?.let { match ->
                        MongoDatabase(databaseName = match.groups["databaseName"]!!.value, clientSettings = MongoClientSettings.builder()
                            .applyConnectionString(ConnectionString(it.url))
                            .build(), atlasSearch = false)
                    }
                    ?: throw IllegalStateException("Invalid mongodb URL. The URL should match the pattern: mongodb://[credentials and host information]/[databaseName]?[params]")
            }
            DatabaseSettings.register("mongodb+srv") {
                Regex("""mongodb\+srv://.*/(?<databaseName>[^?]+)(?:\?.*)?""")
                    .matchEntire(it.url)
                    ?.let { match ->
                        val poolMax = if(Settings.isServerless) 4 else 100
                        val atlasSearch = it.url.contains("atlasSearch=true")
                        val withoutAtlasSearch = it.url.replace("?atlasSearch=true", "").replace("&atlasSearch=true", "")
                        MongoDatabase(databaseName = match.groups["databaseName"]!!.value, clientSettings = MongoClientSettings.builder()
                            .applyConnectionString(ConnectionString(withoutAtlasSearch))
                            .build()
                        , atlasSearch = atlasSearch)
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
                        MongoDatabase(databaseName = "default", clientSettings = testMongo(version = params?.get("mongoVersion")?.firstOrNull()), atlasSearch = false)
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
                        MongoDatabase(databaseName = params?.get("databaseName")?.firstOrNull() ?: "default", clientSettings = embeddedMongo(
                            databaseFolder = File(folder),
                            port = params?.get("port")?.firstOrNull()?.toIntOrNull(),
                            version = params?.get("mongoVersion")?.firstOrNull(),
                        ), atlasSearch = false)

                    }
                    ?: throw IllegalStateException("Invalid mongodb-file URL. The URL should match the pattern: mongodb-file://[FolderPath]?[params]\nAvailable params are: mongoVersion, port, databaseName")
            }
        }
    }

    private val active = AtomicInteger(0)
    private val poolSize by lazy { if(Settings.isServerless) 4 else 100 }
    val listener = object: ConnectionPoolListener {
        override fun connectionCheckedIn(event: ConnectionCheckedInEvent) {
            active.incrementAndGet()
        }

        override fun connectionCheckedOut(event: ConnectionCheckedOutEvent) {
            active.decrementAndGet()
        }
    }

    // You might be asking, "WHY?  WHY IS THIS SO COMPLICATED?"
    // Well, we have to be able to fully disconnect and reconnect exising Mongo databases in order to support AWS's
    // SnapStart feature effectively.  As such, we have to destroy and reproduce all the connections on demand.
    private val makeClientWithListener = {
        active.set(0)
        MongoClient.create(MongoClientSettings.builder(clientSettings)
            .uuidRepresentation(UuidRepresentation.STANDARD)
            .applyToConnectionPoolSettings {
                it.maxSize(poolSize)
                if (Settings.isServerless) {
                    it.maxConnectionIdleTime(15, TimeUnit.SECONDS)
                    it.maxConnectionLifeTime(1L, TimeUnit.MINUTES)
                }
            }
            .build())
    }
    private var client = lazy(makeClientWithListener)
    private var databaseLazy = lazy { client.value.getDatabase(databaseName) }
    val database get() = databaseLazy.value
    private var coroutineCollections =
        ConcurrentHashMap<Pair<KSerializer<*>, String>, Lazy<MongoCollection<BsonDocument>>>()

    override suspend fun disconnect() {
        if (client.isInitialized()) client.value.close()
        client = lazy(makeClientWithListener)
        databaseLazy = lazy { client.value.getDatabase(databaseName) }
        coroutineCollections = ConcurrentHashMap()
    }

    override suspend fun connect() {
        // KEEP THIS AROUND.
        // This initializes the database call at startup.
        healthCheck()
    }

    private val poolHealth: HealthStatus get() = when(val amount = active.get() / poolSize.toFloat()) {
        in 0f ..< 0.7f -> HealthStatus(HealthStatus.Level.OK)
        in 0.7f ..< 0.95f -> HealthStatus(HealthStatus.Level.WARNING, additionalMessage = "Connection utilization: ${amount.times(100).roundToInt()}%")
        in 0.95f ..< 1f -> HealthStatus(HealthStatus.Level.URGENT, additionalMessage = "Connection utilization: ${amount.times(100).roundToInt()}%")
        else -> HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Connection utilization: ${amount.times(100).roundToInt()}%")
    }
    override suspend fun healthCheck(): HealthStatus {
        return listOf(super.healthCheck(), poolHealth).maxBy { it.level }
    }

    private val collections = ConcurrentHashMap<Pair<KSerializer<*>, String>, Lazy<MongoFieldCollection<*>>>()

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(serializer: KSerializer<T>, name: String): MongoFieldCollection<T> =
        (collections.getOrPut(serializer to name) {
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                MongoFieldCollection(serializer, atlasSearch = atlasSearch, object : MongoCollectionAccess {
                    override suspend fun <T> wholeDb(action: suspend com.mongodb.kotlin.client.coroutine.MongoDatabase.() -> T): T {
                        return action(databaseLazy.value)
                    }
                    override suspend fun <T> run(action: suspend MongoCollection<BsonDocument>.() -> T): T = run2(action, 0)
                    suspend fun <T> run2(action: suspend MongoCollection<BsonDocument>.() -> T, tries: Int = 0): T {
                        val it = (coroutineCollections.getOrPut(serializer to name) {
                            lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
                                databaseLazy.value.getCollection(name, BsonDocument::class.java)
                            }
                        } as Lazy<MongoCollection<BsonDocument>>).value
                        try {
                            return action(it)
                        } catch (e: MongoBulkWriteException) {
                            if (e.writeErrors.all { ErrorCategory.fromErrorCode(it.code) == ErrorCategory.DUPLICATE_KEY })
                                throw UniqueViolationException(
                                    cause = e,
                                    collection = it.namespace.collectionName
                                )
                            else throw e
                        } catch (e: MongoSocketException) {
                            if(tries >= 2) throw e
                            else {
                                disconnect()
                                return run2(action, tries + 1)
                            }
                        } catch (e: MongoException) {
                            if (ErrorCategory.fromErrorCode(e.code) == ErrorCategory.DUPLICATE_KEY)
                                throw UniqueViolationException(
                                    cause = e,
                                    collection = it.namespace.collectionName
                                )
                            else throw e
                        } catch(e: Exception) {
                            throw e
                        }
                    }
                })
            }
        } as Lazy<MongoFieldCollection<T>>).value
}
