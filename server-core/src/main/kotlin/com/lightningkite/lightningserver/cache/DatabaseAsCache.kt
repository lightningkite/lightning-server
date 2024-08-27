//package com.lightningkite.lightningserver.cache
//
//import com.lightningkite.lightningdb.*
import com.lightningkite.serialization.*
//import com.lightningkite.lightningserver.logger
//import com.lightningkite.lightningserver.serialization.Serialization
//import com.lightningkite.now
//import kotlinx.datetime.Instant
//import kotlinx.serialization.KSerializer
//import kotlinx.serialization.Serializable
//import kotlin.time.Duration
//import java.util.concurrent.ConcurrentHashMap
//import kotlin.time.Duration.Companion.days
//
///**
// * A Cache implementation that exists entirely in the applications Heap. There are no external connections.
// * This is NOT meant for persistent or long term storage. This cache will be completely erased everytime the application is stopped.
// * This is useful in places that persistent data is not needed and speed is desired such as Unit Tests
// */
//open class DatabaseAsCache(val database: ()->Database, val maxExpiry: Duration = 1.days) : Cache {
//    fun collection() = database().collection<CacheEntry>(CacheEntry.serializer(), "CacheEntry")
//    @Suppress("UNCHECKED_CAST")
//    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
//        return collection().get(key)?.value?.let { Serialization.json.decodeFromString(serializer, it) }
//    }
//
//    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) {
//        collection().upsertOneById(key, CacheEntry(key, Serialization.json.encodeToString(serializer, value), now() + (timeToLive ?: maxExpiry)))
//    }
//
//    override suspend fun <T> setIfNotExists(
//        key: String,
//        value: T,
//        serializer: KSerializer<T>,
//        timeToLive: Duration?
//    ): Boolean {
//        collection().upsertOneById(key, CacheEntry(key, Serialization.json.encodeToString(serializer, value), now() + (timeToLive ?: maxExpiry)))
//    }
//
//    override suspend fun add(key: String, value: Int, timeToLive: Duration?) {
//        val entry = entries[key]?.takeIf { it.expires == null || it.expires > System.currentTimeMillis() }
//        val current = entry?.value
//        val new = when (current) {
//            is Byte -> (current + value).toByte()
//            is Short -> (current + value).toShort()
//            is Int -> (current + value)
//            is Long -> (current + value)
//            is Float -> (current + value)
//            is Double -> (current + value)
//            else -> value
//        }
//        entries[key] = Entry(new, timeToLive?.inWholeMilliseconds?.let { System.currentTimeMillis() + it })
//    }
//
//    override suspend fun remove(key: String) {
//        entries.remove(key)
//    }
//}
//
//@Serializable
//@GenerateDataClassPaths
//data class CacheEntry(override val _id: String, val value: String, val expires: Instant): HasId<String>