package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.GeneralServerSettings
import com.lightningkite.lightningserver.Locationed
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpecMap
import com.lightningkite.lightningserver.ScheduledTask
import com.lightningkite.lightningserver.SecretBasis
import com.lightningkite.lightningserver.ServerPathHandlers
import com.lightningkite.lightningserver.ServerSetting
import com.lightningkite.lightningserver.Task
import com.lightningkite.lightningserver.setting
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import com.lightningkite.services.MetricSink
import kotlinx.serialization.Serializer
import kotlinx.serialization.modules.SerializersModule

public interface ServerDefinition {
    public val internalSerializersModule: SerializersModule
    public val externalSerializersModule: SerializersModule

    public val endpoints: PathSpecMap<ServerPathHandlers>
    public val schedules: Map<PathSpec0, ScheduledTask>
    public val tasks: Map<PathSpec0, Task<*>>
    public val webSocketTopics: PathSpecMap<WebSocketTopic<*, *>>
    public val settings: Map<PathSpec0, ServerSetting<*, *>>
    public val extensions: Extensions

    public val modules: Map<PathSpec0, ServerDefinition>
}

@Suppress("UNCHECKED_CAST")
public val ServerDefinition.generalServerSettings: Locationed<PathSpec0, ServerSetting<GeneralServerSettings, GeneralServerSettings>> get() {
    val location = PathSpec0("general")
    return Locationed(location, settings[location]!! as ServerSetting<GeneralServerSettings, GeneralServerSettings>)
}
@Suppress("UNCHECKED_CAST")
public val ServerDefinition.metrics: Locationed<PathSpec0, ServerSetting<MetricSink.Settings, MetricSink>> get() {
    val location = PathSpec0("metrics")
    return Locationed(location, settings[location]!! as ServerSetting<MetricSink.Settings, MetricSink>)
}
@Suppress("UNCHECKED_CAST")
public val ServerDefinition.secretBasis: Locationed<PathSpec0, ServerSetting<SecretBasis, SecretBasis>> get() {
    val location = PathSpec0("secretBasis")
    return Locationed(location, settings[location]!! as ServerSetting<SecretBasis, SecretBasis>)
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