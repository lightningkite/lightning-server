package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.exceptions.exceptionSettings
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.logging.loggingSettings
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
        sealed = true
        values.putAll(map.mapValues { Box(it.value) })
        val missing = requirements.keys - values.keys
        populate(missing.associateWith { requirements[it]!!.default })
    }

    data class Requirement<Serializable, Goal>(
        val name: String,
        val serializer: KSerializer<Serializable>,
        val default: Serializable,
        val optional: Boolean,
        val getter: (Serializable) -> Goal
    ) : () -> Goal {
        val value by lazy<Goal> {
            @Suppress("UNCHECKED_CAST")
            getter(values[name]?.item as? Serializable ?: default)
        }

        override fun invoke(): Goal = value
    }

    val isServerless: Boolean by lazy {
        System.getenv("FUNCTIONS_WORKER_RUNTIME") != null || System.getenv("AWS_EXECUTION_ENV") != null
    }
}

inline fun <reified Goal> setting(
    name: String,
    default: Goal,
    optional: Boolean = false
): Settings.Requirement<Goal, Goal> {
    @Suppress("UNCHECKED_CAST")
    if (Settings.requirements.containsKey(name)) return Settings.requirements[name] as Settings.Requirement<Goal, Goal>
    if (Settings.sealed) throw Error("Settings have already been set; you cannot add more requirements now.  Attempted to add '$name'")
    val req = Settings.Requirement(name, Serialization.module.serializer(), default, optional) { it }
    Settings.requirements[name] = req
    return req
}

inline fun <reified Serializable : () -> Goal, Goal> setting(
    name: String,
    default: Serializable,
    optional: Boolean = false
): Settings.Requirement<Serializable, Goal> {
    @Suppress("UNCHECKED_CAST")
    if (Settings.requirements.containsKey(name)) return Settings.requirements[name] as Settings.Requirement<Serializable, Goal>
    if (Settings.sealed) throw Error("Settings have already been set; you cannot add more requirements now.  Attempted to add '$name'")
    val req = Settings.Requirement(name, Serialization.module.serializer(), default, optional) { it() }
    Settings.requirements[name] = req
    return req
}
