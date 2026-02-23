package com.lightningkite.lightningserver.cache

import com.lightningkite.now
import kotlinx.datetime.Instant
import kotlinx.serialization.KSerializer
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/**
 * A Cache implementation that exists entirely in the applications Heap. There are no external connections.
 * This is NOT meant for persistent or long term storage. This cache will be completely erased everytime the application is stopped.
 * This is useful in places that persistent data is not needed and speed is desired such as Unit Tests
 */
open class LocalCache(val entries: ConcurrentHashMap<String, Entry> = ConcurrentHashMap()) : Cache {
    companion object : LocalCache()
    data class Entry(val value: Any?, val expires: Instant? = null)

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? =
        entries[key]?.takeIf { it.expires == null || it.expires > now() }?.value as? T

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) {
        entries[key] = Entry(value, timeToLive?.let { now() + it })
    }

    fun clear() {
        entries.clear()
    }

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?,
    ): Boolean {
        // We can't prove success using the response from the compute function, so we must manually handle it.
        var success = false
        entries.compute(key) { _, existing ->
            if (existing == null || (existing.expires != null && existing.expires <= now())) {
                success = true
                Entry(value, timeToLive?.let { now() + it })
            } else existing
        }
        return success
    }

    override suspend fun add(key: String, value: Int, timeToLive: Duration?) {
        val entry = entries[key]?.takeIf { it.expires == null || it.expires > now() }
        val current = entry?.value
        val new = when (current) {
            is Byte -> (current + value).toByte()
            is Short -> (current + value).toShort()
            is Int -> (current + value)
            is Long -> (current + value)
            is Float -> (current + value)
            is Double -> (current + value)
            else -> value
        }
        entries[key] = Entry(new, timeToLive?.let { now() + it })
    }

    override suspend fun remove(key: String) {
        entries.remove(key)
    }
}