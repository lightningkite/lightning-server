package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.definition.Locationed
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.PathSpec2
import com.lightningkite.lightningserver.pathing.PathSpec3
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import kotlin.time.Instant


context(server: ServerRuntime)
public operator fun <SERIALIZABLE, GOAL> ServerSetting<SERIALIZABLE, GOAL>.invoke(): GOAL =
    server.settings.get(this)

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

context(serverRuntime: ServerRuntime) public suspend operator fun <T> Task<T>.invoke(input: T): Unit =
    with(serverRuntime) {
        this@invoke.invoke(input)
    }

context(server: ServerRuntime)
public fun now(): Instant = server.clock.now()


context(runner: ServerRuntime)
public val serverRuntime: ServerRuntime get() = runner

public context(runner: ServerRuntime) val <P: PathSpec> HttpHandler<P>.location: HttpEndpoint<P> get() = runner.server.location(this)
public context(runner: ServerRuntime) val <P: PathSpec> WebSocketHandler<P, *>.location: P get() = runner.server.location(this)
public context(runner: ServerRuntime) val <P: PathSpec> WebSocketTopic<P, *>.location: P get() = runner.server.location(this)
public context(runner: ServerRuntime) val Task<*>.location: PathSpec0 get() = runner.server.location(this)
public context(runner: ServerRuntime) val StartupTask.location: PathSpec0 get() = runner.server.location(this)
public context(runner: ServerRuntime) val ScheduledTask.location: PathSpec0 get() = runner.server.location(this)
