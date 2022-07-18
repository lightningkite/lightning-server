package com.lightningkite.lightningserver.pubsub

import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.settings.Pluggable
import com.lightningkite.lightningserver.settings.setting
import kotlinx.serialization.Serializable

@Serializable
data class PubSubSettings(
    val uri: String = "local"
): ()->PubSubInterface {
    companion object: Pluggable<PubSubSettings, PubSubInterface>() {
        init {
            register("local") { LocalPubSub }
        }
    }

    override fun invoke(): PubSubInterface = parse(this.uri.substringBefore("://"), this)
//    override fun invoke(): PubSubInterface = when {
//        uri == "local" -> LocalPubSub
//        uri == "redis" -> {
//            val redisServer = RedisServer.builder()
//                .port(6379)
//                .setting("bind 127.0.0.1") // good for local development on Windows to prevent security popups
//                .slaveOf("localhost", 6378)
//                .setting("daemonize no")
//                .setting("appendonly no")
//                .setting("maxmemory 128M")
//                .build()
//            redisServer.start()
//            RedisPubSub(RedisClient.create("redis://127.0.0.1:6378"))
//        }
//        uri.startsWith("redis://") -> RedisPubSub(RedisClient.create(uri))
//        else -> throw NotImplementedError("PubSub URI $uri not recognized")
//    }
}
