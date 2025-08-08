package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpecMap
import com.lightningkite.lightningserver.ScheduledTask
import com.lightningkite.lightningserver.ServerPathEndpoints
import com.lightningkite.lightningserver.ServerSetting
import com.lightningkite.lightningserver.Task
import com.lightningkite.lightningserver.websockets.WebSocketTopic

public interface ServerDefinition {
    public val endpoints: PathSpecMap<ServerPathEndpoints>
    public val schedules: Map<PathSpec0, ScheduledTask>
    public val tasks: Map<PathSpec0, Task<*>>
    public val webSocketTopics: PathSpecMap<WebSocketTopic<*, *>>
    public val settings: Map<PathSpec0, ServerSetting<*, *>>
    public val extensions: Extensions

    public val modules: Map<PathSpec0, ServerDefinition>
}

public interface Extensions {
    public interface Key<T : Any>

    public operator fun <T : Any> get(key: Key<T>): T?
    public val entries: Set<Map.Entry<Key<*>, Any?>>
}

public class MutableExtensions: Extensions {
    private val _extensions: MutableMap<Extensions.Key<*>, Any> = HashMap()
    @Suppress("UNCHECKED_CAST")
    override operator fun <T : Any> get(key: Extensions.Key<T>): T? = _extensions[key] as? T
    public operator fun <T : Any> set(key: Extensions.Key<T>, value: T) {
        _extensions[key] = value
    }

    override val entries: Set<Map.Entry<Extensions.Key<*>, Any?>>
        get() = _extensions.entries
}