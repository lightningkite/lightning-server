package com.lightningkite.lightningserver.cache

import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.settings.Pluggable
import com.lightningkite.lightningserver.settings.setting
import kotlinx.serialization.Serializable

@Serializable
data class CacheSettings(
    val uri: String = "local",
    val connectionString: String? = null,
    val databaseNumber: Int? = null
): ()->CacheInterface {

    companion object: Pluggable<CacheSettings, CacheInterface>() {
        init {
            register("local") { LocalCache }
        }
    }
    val cache: CacheInterface by lazy { parse(uri.substringBefore("://"), this) }
//    val cache: CacheInterface by lazy {
//        when {
//            uri == "local" -> LocalCache
//            uri == "redis" -> {
//                val redisServer = RedisServer.builder()
//                    .port(6379)
//                    .setting("bind 127.0.0.1") // good for local development on Windows to prevent security popups
//                    .slaveOf("localhost", 6378)
//                    .setting("daemonize no")
//                    .setting("appendonly no")
//                    .setting("maxmemory 128M")
//                    .build()
//                redisServer.start()
//                RedisCache(RedisClient.create("redis://127.0.0.1:6378"))
//            }
//            uri.startsWith("redis://") -> RedisCache(RedisClient.create(uri + (connectionString ?: "")))
//            else -> throw NotImplementedError("PubSub URI $uri not recognized")
//        }
//    }
    override fun invoke(): CacheInterface = cache
}
