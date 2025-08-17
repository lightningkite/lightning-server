package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.Task
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.MetricSink
import com.lightningkite.services.SettingContext
import kotlinx.html.INPUT
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Clock

public interface ServerRuntime: SettingContext {
    public val server: ServerDefinition

    public val externalSerialization: Serialization
    public val internalSerialization: Serialization
    public val metrics: MetricSink

    override val clock: Clock get() = Clock.System
    public val settings: ServerSettings
    public suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>)
    public suspend fun <T> Locationed<PathSpec0, Task<T>>.invoke(input: T)

    override val internalSerializersModule: SerializersModule get() = internalSerialization.serializersModule
}

