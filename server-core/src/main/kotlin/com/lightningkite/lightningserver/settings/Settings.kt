package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.exceptions.exceptionSettings
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.logging.loggingSettings
import com.lightningkite.lightningserver.metrics.Metricable
import com.lightningkite.lightningserver.metrics.metricsCleanSchedule
import com.lightningkite.lightningserver.metrics.metricsSettings
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import java.util.concurrent.ConcurrentHashMap

@kotlinx.serialization.Serializable(SettingsSerializer::class)
object Settings {
    var lazyLoadResources = false
    var sealed = false
        private set

    fun populate(map: Map<String, Any?>) {
        if(sealed) throw IllegalStateException("Settings have already been populated.")
        sealed = true
        values.putAll(map.mapValues { Box(it.value) })
        val missing = requirements.keys - values.keys
        if (requirements.filter { it.key in missing }.any { !it.value.optional }) {
            throw SerializationException("Settings for ${missing.joinToString()} are missing.")
        }
        if (!lazyLoadResources)
            requirements.values.forEach {
                logger.debug("Loading setting ${it.name}...")
                it()
            }
    }

    private data class Box<T>(val item: T)

    private val values = ConcurrentHashMap<String, Box<*>>()
    internal fun current(): Map<String, Any?> = values.mapValues { it.value.item }
    val requirements = HashMap<String, Requirement<*, *>>()

    init {
        generalSettings
        loggingSettings
        exceptionSettings
        metricsSettings
        metricsCleanSchedule
    }

    fun populateDefaults(map: Map<String, Any?> = mapOf()) {
        val missing = requirements.keys - map.keys
        populate(missing.associateWith { requirements[it]!!.default } + map)
    }

    data class Requirement<Serializable, Goal>(
        val name: String,
        val serializer: KSerializer<Serializable>,
        val default: Serializable,
        val optional: Boolean,
        val getter: (Serializable) -> Goal
    ) : () -> Goal {
        private val value by lazy<Goal> {
            @Suppress("UNCHECKED_CAST")
            getter(values[name]?.item as? Serializable ?: default)
        }
        override fun invoke(): Goal = value
    }

    val isServerless: Boolean by lazy {
        System.getenv("FUNCTIONS_WORKER_RUNTIME") != null || System.getenv("AWS_EXECUTION_ENV") != null
    }
}

fun <Setting, Result> setting(
    name: String,
    default: Setting,
    serializer: KSerializer<Setting>,
    optional: Boolean = false,
    getter: (Setting)->Result,
): Settings.Requirement<Setting, Result> {
    @Suppress("UNCHECKED_CAST")
    if (Settings.requirements.containsKey(name)) return Settings.requirements[name] as Settings.Requirement<Setting, Result>
    if (Settings.sealed) throw Error("Settings have already been set; you cannot add more requirements now.  Attempted to add '$name'")
    val req = Settings.Requirement(name, serializer, default, optional, getter)
    Settings.requirements[name] = req
    return req
}

inline fun <reified Goal> setting(
    name: String,
    default: Goal,
    optional: Boolean = false
): Settings.Requirement<Goal, Goal> = setting<Goal, Goal>(
    name = name,
    default = default,
    optional = optional,
    serializer = Serialization.module.serializer<Goal>(),
    getter = { it }
)

@JvmName("settingInvokable")
inline fun <reified Serializable : () -> Goal, Goal> setting(
    name: String,
    default: Serializable,
    optional: Boolean = false
): Settings.Requirement<Serializable, Goal> = setting<Serializable, Goal>(
    name = name,
    default = default,
    optional = optional,
    serializer = Serialization.module.serializer<Serializable>(),
    getter = { it() }
)


//@JvmName("settingInvokableMetricable")
//inline fun <reified Serializable : () -> Goal, Goal: Metricable<Goal>> setting(
//    name: String,
//    default: Serializable,
//    optional: Boolean = false
//): Settings.Requirement<Serializable, Goal> = setting<Serializable, Goal>(
//    name = name,
//    default = default,
//    optional = optional,
//    serializer = Serialization.module.serializer<Serializable>(),
//    getter = { it().withMetrics(name) }
//)
