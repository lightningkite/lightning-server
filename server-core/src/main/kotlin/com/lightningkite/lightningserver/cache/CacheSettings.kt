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
    override fun invoke(): CacheInterface = cache
}
