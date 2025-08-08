package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.Locationed
import com.lightningkite.lightningserver.ServerSetting
import com.lightningkite.lightningserver.Task
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.generalServerSettings
import com.lightningkite.lightningserver.definition.metrics
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
    public val internalSerialization: Serialization
    public val externalSerialization: Serialization
    override val clock: Clock get() = Clock.System
    public operator fun <SERIALIZABLE, GOAL> Locationed<PathSpec0, ServerSetting<SERIALIZABLE, GOAL>>.invoke(): GOAL
    public suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>)
    public suspend fun <T> Locationed<PathSpec0, Task<T>>.invoke(input: T)

    override val metricSink: MetricSink// = server.metrics()
    override val projectName: String// = server.generalServerSettings().projectName
    override val secretBasis: ByteArray// = server.secretBasis().value.encoded
    override val internalSerializersModule: SerializersModule get() = internalSerialization.serializersModule
}

public abstract class ServerRuntimeBase: ServerRuntime {
    override val metricSink: MetricSink by lazy { server.metrics() }
    override val projectName: String by lazy { server.generalServerSettings().projectName }
    override val secretBasis: ByteArray by lazy { server.secretBasis().bytes }
}


context(serverRuntime: ServerRuntime) public operator fun <SERIALIZABLE, GOAL> Locationed<PathSpec0, ServerSetting<SERIALIZABLE, GOAL>>.invoke(): GOAL
        = with(serverRuntime) { invoke() }

context(serverRuntime: ServerRuntime) public suspend fun <T> WebSocketTopic<PathSpec0, T>.send(value: T): Unit =
    serverRuntime.sendWebSocketSubscriptionMessage(
        WebSocketSubscriptionMessage(this, listOf(), value)
    )

context(serverRuntime: ServerRuntime) public suspend fun <A, T> WebSocketTopic<PathSpec1<A>, T>.send(
    path1: A,
    value: T
): Unit = serverRuntime.sendWebSocketSubscriptionMessage(
    WebSocketSubscriptionMessage(this, listOf(path1), value)
)

context(serverRuntime: ServerRuntime) public suspend fun <A, B, T> WebSocketTopic<PathSpec2<A, B>, T>.send(
    path1: A,
    path2: B,
    value: T
): Unit = serverRuntime.sendWebSocketSubscriptionMessage(
    WebSocketSubscriptionMessage(this, listOf(path1, path2), value)
)

context(serverRuntime: ServerRuntime) public suspend fun <A, B, C, T> WebSocketTopic<PathSpec3<A, B, C>, T>.send(
    path1: A,
    path2: B,
    path3: C,
    value: T
): Unit = serverRuntime.sendWebSocketSubscriptionMessage(
    WebSocketSubscriptionMessage(this, listOf(path1, path2, path3), value)
)