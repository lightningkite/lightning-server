package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.PathSpec2
import com.lightningkite.lightningserver.pathing.PathSpec3
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import kotlin.time.Instant


context(server: ServerRuntime)
public operator fun <SERIALIZABLE, GOAL> ServerSetting<SERIALIZABLE, GOAL>.invoke(): GOAL =
    server.settings.get(this, server)

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

context(server: ServerRuntime)
public fun now(): Instant = server.clock.now()