package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import com.lightningkite.services.MetricReporter
import com.lightningkite.services.SettingContext
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Clock

public interface ServerRuntime: SettingContext {
    public val server: ServerDefinition

    public val externalSerialization: Serialization
    public val internalSerialization: Serialization
    public val metrics: MetricReporter

    override val clock: Clock get() = Clock.System
    public val settings: ServerSettings
    public suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>)
    public suspend fun <T> Task<T>.invoke(input: T)

    override val internalSerializersModule: SerializersModule get() = internalSerialization.serializersModule

    public val serverId: String
    public val serverVersion: String
}

