package com.lightningkite.lightningserver.cache

import com.lightningkite.lightningserver.serverhealth.HealthCheckable
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.settings.Pluggable
import com.lightningkite.lightningserver.settings.setting
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CacheSettings(
    val url: String = "local",
    @SerialName("uri") val legacyUri: String? = null,
    val connectionString: String? = null,
    val databaseNumber: Int? = null
): ()->CacheInterface {

    companion object: Pluggable<CacheSettings, CacheInterface>() {
        init {
            register("local") { LocalCache }
        }
    }
    val cache: CacheInterface by lazy { parse((legacyUri ?: url).substringBefore("://"), this.copy(url = legacyUri ?: url)) }
    override fun invoke(): CacheInterface = cache
}
