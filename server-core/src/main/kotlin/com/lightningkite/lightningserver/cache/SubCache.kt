package com.lightningkite.lightningserver.cache

import com.lightningkite.lightningserver.serverhealth.HealthStatus
import kotlinx.serialization.KSerializer
import java.time.Duration

class PrefixCache(val cache: Cache, val prefix: String): Cache {
    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? = cache.get(prefix + key, serializer)
    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) = cache.set(prefix + key, value, serializer, timeToLive)
    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?
    ): Boolean = cache.setIfNotExists(prefix + key, value, serializer, timeToLive)
    override suspend fun <T> modify(
        key: String,
        serializer: KSerializer<T>,
        maxTries: Int,
        timeToLive: Duration?,
        modification: (T?) -> T?
    ): Boolean = cache.modify(prefix + key, serializer, maxTries, timeToLive, modification)
    override suspend fun add(key: String, value: Int, timeToLive: Duration?) = cache.add(prefix + key, value, timeToLive)
    override suspend fun remove(key: String) = cache.remove(prefix + key)
    override suspend fun healthCheck(): HealthStatus = cache.healthCheck()
    override fun withMetrics(metricsKeyName: String): Cache = cache.withMetrics(metricsKeyName)
}