package com.lightningkite.lightningserver.cache

import kotlinx.serialization.KSerializer
import java.util.concurrent.ConcurrentHashMap

object LocalCache: CacheInterface {
    data class Entry(val value: Any?, val expires: Long? = null)
    val entries = ConcurrentHashMap<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? = entries[key]?.takeIf { it.expires == null || it.expires > System.currentTimeMillis() }?.value as? T

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLiveMilliseconds: Long?) {
        entries[key] = Entry(value, timeToLiveMilliseconds?.let { System.currentTimeMillis() + it })
    }

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>
    ): Boolean {
        if(entries[key] == null) {
            entries[key] = Entry(value, null)
            return true
        }
        return false
    }

    override suspend fun add(key: String, value: Int) {
        val entry = entries[key]?.takeIf { it.expires == null || it.expires > System.currentTimeMillis() }
        val current = entry?.value
        val new = when(current) {
            is Byte -> (current + value).toByte()
            is Short -> (current + value).toShort()
            is Int -> (current + value)
            is Long -> (current + value)
            is Float -> (current + value)
            is Double -> (current + value)
            else -> value
        }
        entries[key] = Entry(value, entry?.expires)
    }

    override suspend fun clear() {
        entries.clear()
    }

    override suspend fun remove(key: String) {
        entries.remove(key)
    }
}