package com.lightningkite.lightningdb

import com.github.jershell.kbson.Configuration
import com.github.jershell.kbson.KBson
import com.lightningkite.lightningserver.db.DatabaseSettings
import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.reactivestreams.client.MongoClient
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.bson.UuidRepresentation
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import org.litote.kmongo.serialization.configuration
import org.litote.kmongo.serialization.kmongoSerializationModule
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KType

class MongoDatabase(val database: CoroutineDatabase, var ensureIndexesReady: Boolean = false) : Database {
    init {
        registerRequiredSerializers()
    }

    constructor(client: MongoClient, databaseName: String) : this(client.getDatabase(databaseName).coroutine) {}
    constructor(
        client: MongoClient,
        databaseName: String,
        ensureIndexesReady: Boolean = false
    ) : this(client.getDatabase(databaseName).coroutine, ensureIndexesReady) {
    }

    companion object {
        val bson by lazy { KBson(serializersModule = kmongoSerializationModule, configuration = configuration) }

        init {
            DatabaseSettings.register("mongodb") {
                KMongo.createClient(
                    MongoClientSettings.builder()
                        .applyConnectionString(ConnectionString(it.url))
                        .uuidRepresentation(UuidRepresentation.STANDARD)
                        .build()
                ).database(it.databaseName)
            }
            DatabaseSettings.register("mongodb-test") {
                testMongo().database(it.databaseName)
            }
            DatabaseSettings.register("mongodb-file") {
                embeddedMongo(File(it.url.removePrefix("mongodb-file://"))).database(
                    it.databaseName
                )
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
                ).also {
                    if (ensureIndexesReady) {
                        runBlocking {
                            @Suppress("OPT_IN_USAGE")
                            it.handleIndexes(GlobalScope)
                        }
                    }
                }
            }
        } as Lazy<MongoFieldCollection<T>>).value
}

fun MongoClient.database(name: String, ensureIndexesReady: Boolean = false): MongoDatabase =
    MongoDatabase(this, name, ensureIndexesReady)