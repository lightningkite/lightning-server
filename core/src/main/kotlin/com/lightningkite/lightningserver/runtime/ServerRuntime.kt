package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.Task
import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.definition.metricsSettings
import com.lightningkite.lightningserver.definition.secretBasis
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.PathSpec2
import com.lightningkite.lightningserver.pathing.PathSpec3
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import com.lightningkite.services.MetricSink
import com.lightningkite.services.SettingContext
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Clock

public interface ServerRuntime: SettingContext {
    public val server: ServerDefinition

    public val externalSerialization: Serialization
    public val internalSerialization: Serialization

    override val clock: Clock get() = Clock.System
    public operator fun <SERIALIZABLE, GOAL> ServerSetting<SERIALIZABLE, GOAL>.invoke(): GOAL
    public suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>)
    public suspend fun <T> Locationed<PathSpec0, Task<T>>.invoke(input: T)

    override val internalSerializersModule: SerializersModule get() = internalSerialization.serializersModule
}

public abstract class ServerRuntimeBase(override val server: ServerDefinition): ServerRuntime {
    override val internalSerialization: Serialization = Serialization(server.internalSerializersModule)
    override val externalSerialization: Serialization = Serialization(server.externalSerializersModule)

    override val metricSink: MetricSink by lazy { metricsSettings() }
    override val projectName: String by lazy { generalSettings().projectName }
    override val secretBasis: ByteArray by lazy { secretBasis().bytes }
}

context(server: ServerRuntime)
public operator fun <SERIALIZABLE, GOAL> ServerSetting<SERIALIZABLE, GOAL>.invoke(): GOAL = with(server) { invoke() }

context(serverRuntime: ServerRuntime)
public suspend fun <T> WebSocketTopic<PathSpec0, T>.send(value: T): Unit =
    serverRuntime.sendWebSocketSubscriptionMessage(
        WebSocketSubscriptionMessage(this, listOf(), value)
    )

context(serverRuntime: ServerRuntime)
public suspend fun <A, T> WebSocketTopic<PathSpec1<A>, T>.send(
    path1: A,
    value: T
): Unit = serverRuntime.sendWebSocketSubscriptionMessage(
    WebSocketSubscriptionMessage(this, listOf(path1), value)
)

context(serverRuntime: ServerRuntime)
public suspend fun <A, B, T> WebSocketTopic<PathSpec2<A, B>, T>.send(
    path1: A,
    path2: B,
    value: T
): Unit = serverRuntime.sendWebSocketSubscriptionMessage(
    WebSocketSubscriptionMessage(this, listOf(path1, path2), value)
)

context(serverRuntime: ServerRuntime)
public suspend fun <A, B, C, T> WebSocketTopic<PathSpec3<A, B, C>, T>.send(
    path1: A,
    path2: B,
    path3: C,
    value: T
): Unit = serverRuntime.sendWebSocketSubscriptionMessage(
    WebSocketSubscriptionMessage(this, listOf(path1, path2, path3), value)
)