package com.lightningkite.lightningserver.cache

import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import java.time.Duration

@Deprecated("Renamed to just 'cache'", ReplaceWith("Cache", "com.lightningkite.lightningserver.cache.Cache"))
typealias CacheInterface = Cache
interface Cache : HealthCheckable {
    suspend fun <T> get(key: String, serializer: KSerializer<T>): T?
    suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration? = null)
    suspend fun <T> setIfNotExists(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration? = null): Boolean
    suspend fun <T> modify(
        key: String,
        serializer: KSerializer<T>,
        maxTries: Int = 1,
        timeToLive: Duration? = null,
        modification: (T?) -> T?
    ): Boolean {
        repeat(maxTries) {
            val current = get(key, serializer)
            val new = modification(current)
            if (current == get(key, serializer)) {
                if (new != null)
                    set(key, new, serializer)
                else
                    remove(key)
                return true
            }
        }
        return false
    }

    suspend fun add(key: String, value: Int, timeToLive: Duration? = null)
    suspend fun clear()
    suspend fun remove(key: String)
    override suspend fun healthCheck(): HealthStatus {
        return try {
            set("health-check-test-key", true)
            if(get<Boolean>("health-check-test-key") == true) {
                HealthStatus(HealthStatus.Level.OK)
            } else {
                HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Could not retrieve set property")
            }
        } catch (e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}

suspend inline fun <reified T : Any> Cache.get(key: String): T? {
    return get(key, Serialization.Internal.json.serializersModule.serializer<T>())
}

suspend inline fun <reified T : Any> Cache.set(key: String, value: T, timeToLive: Duration? = null) {
    return set(key, value, Serialization.Internal.json.serializersModule.serializer<T>(), timeToLive)
}

suspend inline fun <reified T : Any> Cache.setIfNotExists(key: String, value: T, timeToLive: Duration? = null): Boolean {
    return setIfNotExists(key, value, Serialization.Internal.json.serializersModule.serializer<T>(), timeToLive)
}


suspend inline fun <reified T : Any> Cache.modify(
    key: String,
    maxTries: Int = 1,
    timeToLive: Duration? = null,
    noinline modification: (T?) -> T?
): Boolean = modify(key, Serialization.Internal.json.serializersModule.serializer<T>(), maxTries, timeToLive, modification)