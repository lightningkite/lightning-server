package com.lightningkite.lightningserver.cache

import com.lightningkite.lightningserver.SettingSingleton
import com.lightningkite.lightningserver.pubsub.pubSub
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import io.lettuce.core.RedisClient
import kotlinx.serialization.Serializable
import redis.embedded.RedisServer

@Serializable
data class CacheSettings(
    val uri: String = "local"
): HealthCheckable {
    companion object : SettingSingleton<CacheSettings>()

    init {
        instance = this
    }
    override suspend fun healthCheck(): HealthStatus = cache.healthCheck()
    override val healthCheckName: String get() = cache.healthCheckName
}

val cache: CacheInterface by lazy {
    val uri = CacheSettings.instance.uri
    when {
        uri == "local" -> LocalCache
        uri == "redis" -> {
            val redisServer = RedisServer.builder()
                .port(6379)
                .setting("bind 127.0.0.1") // good for local development on Windows to prevent security popups
                .slaveOf("localhost", 6378)
                .setting("daemonize no")
                .setting("appendonly no")
                .setting("maxmemory 128M")
                .build()
            redisServer.start()
            RedisCache(RedisClient.create("redis://127.0.0.1:6378"))
        }
        uri.startsWith("redis://") -> RedisCache(RedisClient.create(uri))
        else -> throw NotImplementedError("PubSub URI $uri not recognized")
    }
}