package com.lightningkite.lightningserver.cache

import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import java.time.Duration

/**
 * A class that handles and manipulates a single key in a cache.
 */
class CacheHandle<T>(val cache: () -> Cache, val key: String, val serializer: KSerializer<T>) {
    suspend fun get() = cache().get(key, serializer)
    suspend fun set(value: T, timeToLive: Duration? = null) = cache().set(key, value, serializer, timeToLive)
    suspend fun setIfNotExists(value: T, timeToLive: Duration? = null): Boolean =
        cache().setIfNotExists(key, value, serializer, timeToLive)

    suspend fun modify(
        maxTries: Int = 1,
        timeToLive: Duration? = null,
        modification: (T?) -> T?,
    ): Boolean = cache().modify(key, serializer, maxTries, timeToLive, modification)

    suspend fun add(value: Int, timeToLive: Duration? = null) = cache().add(key, value, timeToLive)
    suspend fun remove() = cache().remove(key)
}

inline operator fun <reified T> (() -> Cache).get(key: String) =
    CacheHandle<T>(this, key, Serialization.module.serializer())
