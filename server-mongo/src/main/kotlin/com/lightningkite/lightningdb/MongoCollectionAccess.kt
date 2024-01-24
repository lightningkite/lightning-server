package com.lightningkite.lightningdb

import com.mongodb.kotlin.client.coroutine.MongoCollection
import org.bson.BsonDocument

interface MongoCollectionAccess {
    suspend fun <T> run(
        action: suspend MongoCollection<BsonDocument>.() -> T
    ): T
}