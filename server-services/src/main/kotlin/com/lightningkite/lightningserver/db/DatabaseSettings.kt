package com.lightningkite.lightningserver.db

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.CacheSettings
import com.lightningkite.lightningserver.cache.MetricsCache
import com.lightningkite.lightningserver.metrics.Metricable
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.serverhealth.HealthStatus
import com.lightningkite.lightningserver.services.Pluggable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KType

/**
 * Settings that define what database to use and how to connect to it.
 *
 * @param url Defines the type and connection to the database. Examples are ram, ram-preload, ram-unsafe-persist
 * @param databaseName The name of the database to connect to.
 */
@Serializable
data class DatabaseSettings(
    val url: String = "ram-unsafe-persist://${File("./local/database").absolutePath}",
) : Database, Metricable {

    companion object : Pluggable<DatabaseSettings, Database>() {
        init {
            register("ram") { InMemoryDatabase() }
            register("ram-preload") { InMemoryPreloadDatabase(File(it.url.substringAfter("://"))) }
            register("ram-unsafe-persist") { InMemoryUnsafePersistenceDatabase(File(it.url.substringAfter("://"))) }
            register("delay") {
                val x = it.url.substringAfter("://")
                val delay = x.substringBefore("/").toLong()
                val wraps = x.substringAfter("/")
                parse(wraps.substringBefore("://"), DatabaseSettings(wraps)).delayed(delay)
            }
        }
    }

    private var backing: Database? = null
    val wraps: Database
        get() {
        if(backing == null) backing = parse(url.substringBefore("://"), this)
        return backing!!
    }

    override fun applyMetrics(metrics: Metrics, metricsKeyName: String) {
        backing = MetricsWrappedDatabase(backing!!, metrics, metricsKeyName)
    }

    override fun <T : Any> collection(
        module: SerializersModule,
        serializer: KSerializer<T>,
        name: String
    ): FieldCollection<T> = wraps.collection(module, serializer, name)
    override suspend fun healthCheck(): HealthStatus = wraps.healthCheck()
    override suspend fun connect() = wraps.connect()
    override suspend fun disconnect() = wraps.disconnect()
}
