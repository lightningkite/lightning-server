package com.lightningkite.lightningserver.pubsub

import com.lightningkite.lightningserver.settings.Pluggable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PubSubSettings(
    val url: String = "local",
    @SerialName("uri") val legacyUri: String? = null,
): ()->PubSubInterface {
    companion object: Pluggable<PubSubSettings, PubSubInterface>() {
        init {
            register("local") { LocalPubSub }
        }
    }

    override fun invoke(): PubSubInterface = parse((legacyUri ?: url).substringBefore("://"), this.copy(url = legacyUri ?: url))
}
