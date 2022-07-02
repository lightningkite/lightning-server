package com.lightningkite.lightningserver.pubsub

import com.lightningkite.lightningserver.SettingSingleton
import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import io.lettuce.core.RedisClient
import kotlinx.serialization.Serializable
import redis.embedded.RedisServer

@Serializable
data class PubSubSettings(
    val uri: String = "local"
): HealthCheckable {
    companion object : SettingSingleton<PubSubSettings>()

    init {
        instance = this
    }

    override suspend fun healthCheck(): HealthStatus = pubSub.healthCheck()
    override val healthCheckName: String get() = pubSub.healthCheckName
}

val pubSub: PubSubInterface by lazy {
    val uri = PubSubSettings.instance.uri
    when {
        uri == "local" -> LocalPubSub
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
            RedisPubSub(RedisClient.create("redis://127.0.0.1:6378"))
        }
        uri.startsWith("redis://") -> RedisPubSub(RedisClient.create(uri))
        else -> throw NotImplementedError("PubSub URI $uri not recognized")
    }
}