package com.lightningkite.lightningserver.settings

import com.lightningkite.serialization.contextualSerializerIfHandled
import com.lightningkite.lightningserver.encryption.secretBasis
import com.lightningkite.lightningserver.exceptions.exceptionSettings
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.logging.loggingSettings
import com.lightningkite.lightningserver.metrics.Metricable
import com.lightningkite.lightningserver.metrics.metricsCleanSchedule
import com.lightningkite.lightningserver.metrics.metricsSettings
import com.lightningkite.lightningserver.serialization.Serialization
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object Settings {
    var lazyLoadResources = false
    var sealed = false
        private set

    fun clear() {
        sealed = false
        values.clear()
    }

    fun populate(map: Map<String, Any?>) {
        if (sealed) throw IllegalStateException("Settings have already been populated.")
        values.putAll(map.mapValues { Box(it.value) })
        val missing = requirements.keys - values.keys
        if (requirements.filter { it.key in missing }.any { !it.value.optional }) {
            throw SerializationException("Settings for ${missing.joinToString()} are missing.")
        }
        sealed = true
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
        secretBasis
    }

    fun populateDefaults(map: Map<String, Any?> = mapOf()) {
        val missing = requirements.keys - map.keys
        populate(missing.associateWith { requirements[it]!!.default } + map)
    }

    fun repair() {
        sealed = false
        val missing = requirements.keys - values.keys
        populate(missing.associateWith { requirements[it]!!.default })
    }

    class Requirement<Serializable, Goal>(
        val name: String,
        val serializer: KSerializer<Serializable>,
        val default: Serializable,
        val optional: Boolean,
        val description: String?,
        getter: (Serializable) -> Goal,
    ) : () -> Goal {
        private val alreadyDone = AtomicBoolean(false)
        private val getter: (Serializable) -> Goal = {
            if (!alreadyDone.compareAndSet(false, true)) throw Error()
            getter(it)
        }
        private val value by lazy<Goal> {
            if (!sealed) throw IllegalStateException()
            @Suppress("UNCHECKED_CAST")
            if (values.containsKey(name)) {
                getter(values[name]?.item as Serializable)
            } else {
                getter(default)
            }
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
    description: String? = null,
    getter: (Setting) -> Result,
): Settings.Requirement<Setting, Result> {
    @Suppress("UNCHECKED_CAST")
    if (Settings.requirements.containsKey(name)) return Settings.requirements[name] as Settings.Requirement<Setting, Result>
    if (Settings.sealed) throw Error("Settings have already been set; you cannot add more requirements now.  Attempted to add '$name'")
    val req = Settings.Requirement(name, serializer, default, optional, description, getter)
    Settings.requirements[name] = req
    return req
}

inline fun <reified Goal> setting(
    name: String,
    default: Goal,
    optional: Boolean = false,
    description: String? = null,
): Settings.Requirement<Goal, Goal> = setting<Goal, Goal>(
    name = name,
    default = default,
    optional = optional,
    serializer = Serialization.module.contextualSerializerIfHandled<Goal>(),
    description = description,
    getter = { it }
)

@JvmName("settingInvokable")
inline fun <reified Serializable : () -> Goal, Goal> setting(
    name: String,
    default: Serializable,
    optional: Boolean = false,
    description: String? = null,
): Settings.Requirement<Serializable, Goal> = setting<Serializable, Goal>(
    name = name,
    default = default,
    optional = optional,
    serializer = Serialization.module.contextualSerializerIfHandled<Serializable>(),
    description = description,
    getter = { it() }
)

@JvmName("settingInvokableMetricable")
inline fun <reified Serializable : () -> Goal, Goal : Metricable<Goal>> setting(
    name: String,
    default: Serializable,
    optional: Boolean = false,
    description: String? = null,
): Settings.Requirement<Serializable, Goal> = setting<Serializable, Goal>(
    name = name,
    default = default,
    optional = optional,
    serializer = Serialization.module.contextualSerializerIfHandled<Serializable>(),
    description = description,
    getter = { it().withMetrics(name) }
)
