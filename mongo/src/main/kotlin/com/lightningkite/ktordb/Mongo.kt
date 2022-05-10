package com.lightningkite.ktordb

import com.mongodb.reactivestreams.client.MongoClient
import org.litote.kmongo.coroutine.CoroutineClient
import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.coroutine
import kotlin.reflect.KClass

class MongoDatabase(val database: CoroutineDatabase) : WatchableDatabase {
    constructor(client: MongoClient, databaseName: String):this(client.getDatabase(databaseName).coroutine){}
    override fun <T : Any> collection(clazz: KClass<T>, name: String): MongoFieldCollection<T> {
        return MongoFieldCollection(
            database
                .database
                .getCollection(name, clazz.java)
                .coroutine
        )
    }
}

fun MongoClient.database(name: String): MongoDatabase = MongoDatabase(this, name)