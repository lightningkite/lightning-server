package com.lightningkite.ktorbatteries.pubsub

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.lightningkite.ktorbatteries.SettingSingleton
import io.lettuce.core.RedisClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.File

@Serializable
data class PubSubSettings(
    val uri: String = "local"
) {
    companion object : SettingSingleton<PubSubSettings>()

    init {
        instance = this
    }
}

val pubSub: PubSubInterface by lazy {
    val uri = PubSubSettings.instance.uri
    when {
        uri == "local" -> LocalPubSub
        uri.startsWith("redis://") -> RedisClient.create(uri).connectPubSub().let { RedisPubSub(it) }
        else -> throw NotImplementedError("PubSub URI $uri not recognized")
    }
}