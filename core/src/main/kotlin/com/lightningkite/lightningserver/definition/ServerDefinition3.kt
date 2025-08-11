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

public interface ServerDefinition : Extended {
    public val internalSerializersModule: SerializersModule
    public val externalSerializersModule: SerializersModule

    public val endpoints: PathSpecMap<ServerPathEndpoints>
    public val schedules: Map<PathSpec0, ScheduledTask>
    public val tasks: Map<PathSpec0, Task<*>>
    public val webSocketTopics: PathSpecMap<WebSocketTopic<*, *>>
    public val settings: List<ServerSetting<*, *>>
    public override val extensions: Extensions

    public val modules: Map<PathSpec0, ServerDefinition>
}

public fun ServerDefinition.flatten(): ServerDefinition = if (modules.isEmpty()) this else object : ServerDefinition {
    private val source get() = this@flatten
    private val flattenedModules = source.modules.mapValues { (_, mod) -> mod.flatten() }

    override val internalSerializersModule: SerializersModule = flattenedModules.values.fold(source.internalSerializersModule) { acc, def -> acc + def.internalSerializersModule }
    override val externalSerializersModule: SerializersModule = flattenedModules.values.fold(source.externalSerializersModule) { acc, def -> acc + def.externalSerializersModule }

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

    override val settings: List<ServerSetting<*, *>> =
        (source.settings + flattenedModules.values.flatMap { it.settings }).distinctBy { it.settingName }

    override val modules: Map<PathSpec0, ServerDefinition> = emptyMap()
}

