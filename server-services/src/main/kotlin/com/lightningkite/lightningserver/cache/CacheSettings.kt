package com.lightningkite.lightningserver.cache

import com.lightningkite.lightningserver.metrics.Metricable
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.services.Pluggable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * Settings that define what cache to use and how to connect to it.
 *
 * @param url Defines the type and connection to the cache. Built in options are local.
 */
@Serializable
data class CacheSettings(
    val url: String = "local",
    @SerialName("uri") val legacyUri: String? = null,
    val connectionString: String? = null,
    val databaseNumber: Int? = null
) : Cache, Metricable {

    companion object : Pluggable<CacheSettings, Cache>() {
        init {
            register("local") { LocalCache }
        }
    }

    private var backing: Cache? = null
    val wraps: Cache get() {
        if(backing == null) backing = parse(url.substringBefore("://"), this)
        return backing!!
    }

    override fun applyMetrics(metrics: Metrics, metricsKeyName: String) {
        backing = MetricsCache(backing!!, metrics, metricsKeyName)
    }

    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? = wraps.get(key, serializer)
    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) = wraps.set(key, value, serializer, timeToLive)
    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>,
        timeToLive: Duration?
    ): Boolean = wraps.setIfNotExists(key, value, serializer, timeToLive)
    override suspend fun add(key: String, value: Int, timeToLive: Duration?) = wraps.add(key, value, timeToLive)
    override suspend fun remove(key: String) = wraps.remove(key)
}
