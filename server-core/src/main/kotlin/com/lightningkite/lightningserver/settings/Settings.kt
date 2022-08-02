package com.lightningkite.lightningserver.settings

import com.lightningkite.lightningserver.exceptions.exceptionSettings
import com.lightningkite.lightningserver.logging.loggingSettings
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import kotlinx.serialization.KSerializer
import java.util.concurrent.ConcurrentHashMap
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

@kotlinx.serialization.Serializable(SettingsSerializer::class)
object Settings {
    var sealed = false
        private set
    fun populate(map: Map<String, Any?>){
        sealed = true
        values.putAll(map.mapValues { Box(it.value) })
        val missing = requirements.keys - values.keys
        if(missing.isNotEmpty()) {
            throw IllegalStateException("Settings for ${missing.joinToString()} are missing.")
        }
//        requirements.values.forEach { it() }
    }
    private data class Box<T>(val item: T)
    private val values = ConcurrentHashMap<String, Box<*>>()
    fun current(): Map<String, Any?> = values.mapValues { it.value.item }
    val requirements = HashMap<String, Requirement<*, *>>()
    init {
        generalSettings
        loggingSettings
        exceptionSettings
    }

    fun populateDefaults(map: Map<String, Any?> = mapOf()) {
        sealed = true
        values.putAll(map.mapValues { Box(it.value) })
        val missing = requirements.keys - values.keys
        populate(missing.associateWith { requirements[it]!!.default })
    }

    data class Requirement<Serializable, Goal>(val name: String, val serializer: KSerializer<Serializable>, val default: Serializable, val getter: (Serializable)->Goal): ()->Goal {
        val value by lazy<Goal> {
            @Suppress("UNCHECKED_CAST")
            getter(values[name]!!.item as Serializable)
        }
        override fun invoke(): Goal = value
    }
}

inline fun <reified Goal> setting(name: String, default: Goal): Settings.Requirement<Goal, Goal> {
    @Suppress("UNCHECKED_CAST")
    if(Settings.requirements.containsKey(name)) return Settings.requirements[name] as Settings.Requirement<Goal, Goal>
    if(Settings.sealed) throw Error("Settings have already been set; you cannot add more requirements now.  Attempted to add '$name'")
    val req = Settings.Requirement(name, serializerOrContextual(), default) { it }
    Settings.requirements[name] = req
    return req
}

inline fun <reified Serializable: ()->Goal, Goal> setting(name: String, default: Serializable): Settings.Requirement<Serializable, Goal> {
    @Suppress("UNCHECKED_CAST")
    if(Settings.requirements.containsKey(name)) return Settings.requirements[name] as Settings.Requirement<Serializable, Goal>
    if(Settings.sealed) throw Error("Settings have already been set; you cannot add more requirements now.  Attempted to add '$name'")
    val req = Settings.Requirement(name, serializerOrContextual(), default) { it() }
    Settings.requirements[name] = req
    return req
}
