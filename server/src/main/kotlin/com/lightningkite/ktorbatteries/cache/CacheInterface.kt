package com.lightningkite.ktorbatteries.cache

import com.lightningkite.ktorbatteries.serialization.Serialization
import com.lightningkite.ktorbatteries.serverhealth.HealthCheckable
import com.lightningkite.ktorbatteries.serverhealth.HealthStatus
import kotlinx.coroutines.flow.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

interface CacheInterface: HealthCheckable {
    suspend fun <T> get(key: String, serializer: KSerializer<T>): T?
    suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLiveMilliseconds: Long? = null)
    suspend fun add(key: String, value: Int)
    suspend fun clear()
    suspend fun remove(key: String)
    override suspend fun healthCheck(): HealthStatus {
        return try {
            set("health-check-test-key", true)
            HealthStatus(HealthStatus.Level.OK)
        } catch(e: Exception) {
            HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }

    override val healthCheckName: String
        get() = "Cache"
}
suspend inline fun <reified T: Any> CacheInterface.get(key: String): T? {
    return get(key, Serialization.json.serializersModule.serializer<T>())
}
suspend inline fun <reified T: Any> CacheInterface.set(key: String, value: T, timeToLiveMilliseconds: Long? = null) {
    return set(key, value, Serialization.json.serializersModule.serializer<T>(), timeToLiveMilliseconds)
}

