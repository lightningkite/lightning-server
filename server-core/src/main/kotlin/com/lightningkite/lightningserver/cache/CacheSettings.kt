package com.lightningkite.lightningserver.cache

import com.lightningkite.lightningserver.settings.Pluggable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Settings that define what cache to use and how to connect to it.
 *
 * @param url Defines the type and connection to the cache. Built in options are local.
 */
@Serializable
data class CacheSettings(
    val url: String = "local",
    @SerialName("uri") val legacyUri: String? = null,
    val connectionString: String? = null,
    val databaseNumber: Int? = null
) : () -> Cache {

    companion object : Pluggable<CacheSettings, Cache>() {
        init {
            register("local") { LocalCache }
        }
    }

    override fun invoke(): Cache = parse(url.substringBefore("://"), this).metrics("Cache")
}
