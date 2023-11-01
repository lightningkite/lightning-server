package com.lightningkite.lightningserver.pubsub

import com.lightningkite.lightningserver.notifications.NotificationClient
import com.lightningkite.lightningserver.notifications.NotificationData
import com.lightningkite.lightningserver.notifications.NotificationSettings
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.services.Pluggable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PubSubSettings(
    val url: String = "local"
) : PubSub {
    companion object : Pluggable<PubSubSettings, PubSub>() {
        init {
            register("local") { LocalPubSub }
        }
    }

    private var backing: PubSub? = null
    val wraps: PubSub
        get() {
            if(backing == null) backing = parse(url.substringBefore("://"), this)
            return backing!!
        }

    override fun <T> get(key: String, serializer: KSerializer<T>): PubSubChannel<T> = wraps.get(key, serializer)
    override fun string(key: String): PubSubChannel<String> = wraps.string(key)
    override suspend fun healthCheck(): HealthStatus = wraps.healthCheck()
    override suspend fun connect() = wraps.connect()
    override suspend fun disconnect() = wraps.disconnect()
}
