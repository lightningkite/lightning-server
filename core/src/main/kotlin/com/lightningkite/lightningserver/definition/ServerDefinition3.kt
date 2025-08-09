package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpecMap
import com.lightningkite.lightningserver.ScheduledTask
import com.lightningkite.lightningserver.Task
import com.lightningkite.lightningserver.pathing.MutablePathSpecMap
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.plus
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus

public interface ServerDefinition {
    public val internalSerializersModule: SerializersModule
    public val externalSerializersModule: SerializersModule

    public val endpoints: PathSpecMap<ServerPathEndpoints>
    public val schedules: Map<PathSpec0, ScheduledTask>
    public val tasks: Map<PathSpec0, Task<*>>
    public val webSocketTopics: PathSpecMap<WebSocketTopic<*, *>>
    public val settings: List<ServerSetting<*, *>>
    public val extensions: Extensions

    public val modules: Map<PathSpec0, ServerDefinition>
}

public interface Extensions {
    public interface Key<T : Any>

    public operator fun <T : Any> get(key: Key<T>): T?
    public val entries: Set<Map.Entry<Key<*>, Any>>
}


public fun ServerDefinition.flatten(): ServerDefinition = if (modules.isEmpty()) this else object : ServerDefinition {
    private val source get() = this@flatten
    private val flattenedModules = source.modules.mapValues { (_, mod) -> mod.flatten() }

    override val internalSerializersModule: SerializersModule = source.modules.values.fold(source.internalSerializersModule) { acc, def -> acc + def.internalSerializersModule }
    override val externalSerializersModule: SerializersModule = source.modules.values.fold(source.externalSerializersModule) { acc, def -> acc + def.externalSerializersModule }

    private fun <T> flatten(registry: (ServerDefinition) -> Map<PathSpec0, T>): Map<PathSpec0, T> = buildMap {
        putAll(registry(source))
        for ((modPath, module) in flattenedModules) {
            putAll(registry(module).mapKeys { (path, _) -> modPath + path })
        }
    }
    private fun <T> flattenPathSpec(registry: (ServerDefinition) -> PathSpecMap<T>): PathSpecMap<T> = MutablePathSpecMap<T>().apply {
        putAll(PathSpec.root, registry(source))
        for ((modPath, module) in flattenedModules) {
            putAll(modPath, registry(module))
        }
    }

    override val endpoints: PathSpecMap<ServerPathEndpoints> = flattenPathSpec { it.endpoints }
    override val webSocketTopics: PathSpecMap<WebSocketTopic<*, *>> = flattenPathSpec { it.webSocketTopics }

    override val schedules: Map<PathSpec0, ScheduledTask> = flatten { it.schedules }
    override val tasks: Map<PathSpec0, Task<*>> = flatten { it.tasks }

    override val extensions: Extensions = MutableExtensions().apply {
        include(source.extensions)
        for (module in flattenedModules.values) include(module.extensions)
    }

    override val settings: List<ServerSetting<*, *>> = buildList {
        val alreadyDefined = HashSet<String>()

        fun addSettings(settings: List<ServerSetting<*, *>>) {
            val retained = settings
                .distinctBy { it.settingName }
                .filter { it.settingName !in alreadyDefined }

            addAll(retained)
            alreadyDefined.addAll(retained.map { it.settingName })
        }

        addSettings(source.settings)
        for (module in flattenedModules.values) addSettings(module.settings)
    }

    override val modules: Map<PathSpec0, ServerDefinition> = emptyMap()
}


public class MutableExtensions: Extensions {
    private val _extensions: MutableMap<Extensions.Key<*>, Any> = HashMap()
    @Suppress("UNCHECKED_CAST")
    override operator fun <T : Any> get(key: Extensions.Key<T>): T? = _extensions[key] as? T
    public operator fun <T : Any> set(key: Extensions.Key<T>, value: T) {
        _extensions[key] = value
    }

    override val entries: Set<Map.Entry<Extensions.Key<*>, Any>>
        get() = _extensions.entries

    public fun include(extensions: Extensions) {
        for ((key, value) in extensions.entries) {
            _extensions.putIfAbsent(key, value)
        }
    }
}