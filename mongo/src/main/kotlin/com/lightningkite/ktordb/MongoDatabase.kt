package com.lightningkite.ktordb

import com.github.jershell.kbson.KBson
import com.mongodb.reactivestreams.client.MongoClient
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.serialization.configuration
import org.litote.kmongo.serialization.kmongoSerializationModule
import kotlin.reflect.KClass
import kotlin.reflect.KType

class MongoDatabase(val database: CoroutineDatabase) : Database {
    init {
        fixUuidSerialization()
    }
    constructor(client: MongoClient, databaseName: String):this(client.getDatabase(databaseName).coroutine){}

    companion object {
        val bson by lazy { KBson(serializersModule = kmongoSerializationModule, configuration = configuration) }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> collection(type: KType, name: String): MongoFieldCollection<T> {
        return MongoFieldCollection(
            bson.serializersModule.serializer(type) as KSerializer<T>,
            database
                .database
                .getCollection(name, (type.classifier as KClass<*>).java as Class<T>)
                .coroutine
        )
    }
}

fun MongoClient.database(name: String): MongoDatabase = MongoDatabase(this, name)