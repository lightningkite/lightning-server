package com.lightningkite.lightningserver.cache

import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import net.rubyeye.xmemcached.*
import net.rubyeye.xmemcached.aws.AWSElasticCacheClient
import java.net.InetSocketAddress
import java.time.Duration


class MemcachedCache(val client: MemcachedClient): CacheInterface, HealthCheckable {
    companion object {
        init {
            CacheSettings.register("memcached-test") {
                val process = EmbeddedMemcached.start()
                Runtime.getRuntime().addShutdownHook(Thread {
                    process.destroy()
                })
                MemcachedCache(XMemcachedClient("127.0.0.1", 11211))
            }
            CacheSettings.register("memcached") {
                val hosts = it.uri.substringAfter("://").split(' ', ',').filter { it.isNotBlank() }
                    .map { InetSocketAddress(it.substringBefore(':'), it.substringAfter(':', "").toIntOrNull() ?: 11211) }
                MemcachedCache(XMemcachedClient(hosts))
            }
            CacheSettings.register("memcached-aws") {
                val configFullHost = it.uri.substringAfter("://")
                val configPort = configFullHost.substringAfter(':', "").toIntOrNull() ?: 11211
                val configHost = configFullHost.substringBefore(':')
                val client = AWSElasticCacheClient(InetSocketAddress(configHost, configPort))
                MemcachedCache(client)
            }
        }
    }

    override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? = withContext(Dispatchers.IO) {
        client.get<String>(key)?.let { Serialization.json.decodeFromString(serializer, it) }.also {
        }
    }

    override suspend fun <T> set(key: String, value: T, serializer: KSerializer<T>, timeToLive: Duration?) = withContext(Dispatchers.IO) {
        val succeed = client.set(key, timeToLive?.toSeconds()?.toInt() ?: Int.MAX_VALUE, Serialization.json.encodeToString(serializer, value))
        Unit
    }

    override suspend fun <T> setIfNotExists(
        key: String,
        value: T,
        serializer: KSerializer<T>
    ): Boolean = withContext(Dispatchers.IO) {
        client.add(key, Int.MAX_VALUE, Serialization.json.encodeToString(serializer, value))
    }

    override suspend fun <T> modify(key: String, serializer: KSerializer<T>, maxTries: Int, modification: (T?) -> T?): Boolean = withContext(Dispatchers.IO) {
        client.cas(key, Int.MAX_VALUE, object: CASOperation<String> {
            override fun getMaxTries(): Int = maxTries
            override fun getNewValue(currentCAS: Long, currentValue: String?): String? {
                return currentValue?.let { Serialization.json.decodeFromString(serializer, it) }
                    .let(modification)
                    ?.let { Serialization.json.encodeToString(serializer, it) }
            }
        })
    }

    override suspend fun add(key: String, value: Int) = withContext(Dispatchers.IO) {
        client.incr(key, value.toLong())
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        client.flushAll()
    }

    override suspend fun remove(key: String) = withContext(Dispatchers.IO) {
        client.delete(key)
        Unit
    }

    override suspend fun healthCheck(): HealthStatus = withContext(Dispatchers.IO) {
        try {
            val allEntries = client.stats.values.flatMap { it.entries }.associate { it.key to it.value }
            val used = allEntries["bytes"]?.toLong() ?: 0L
            val available = allEntries["limit_maxbytes"]?.toLong() ?: (1024L * 1024L * 1024L)
            val ratio = used.toDouble() / available.toDouble()
            return@withContext when(ratio) {
                in 0.75..0.85 -> HealthStatus(HealthStatus.Level.WARNING, additionalMessage = "${ratio.times(100).toInt()}%")
                in 0.85..0.9999999 -> HealthStatus(HealthStatus.Level.URGENT, additionalMessage = "${ratio.times(100).toInt()}%")
                1.0 -> HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Memory is full!")
                else -> HealthStatus(HealthStatus.Level.OK, additionalMessage = "${ratio.times(100).toInt()}%")
            }
        } catch(e: Exception) {
            return@withContext HealthStatus(HealthStatus.Level.ERROR, additionalMessage = e.message)
        }
    }
}