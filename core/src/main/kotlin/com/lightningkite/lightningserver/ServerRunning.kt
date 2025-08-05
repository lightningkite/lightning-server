package com.lightningkite.lightningserver

public interface ServerRunning {
    public val server: ServerDefinition
    public operator fun <SERIALIZABLE, GOAL> Locationed<PathSpec0, ServerSetting<SERIALIZABLE, GOAL>>.invoke(): GOAL
    public suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>)
}


context(serverRunning: ServerRunning) public operator fun <SERIALIZABLE, GOAL> Locationed<PathSpec0, ServerSetting<SERIALIZABLE, GOAL>>.invoke(): GOAL
        = with(serverRunning) { invoke() }

context(serverRunning: ServerRunning) public suspend fun <T> WebSocketTopic<PathSpec0, T>.send(value: T): Unit =
    serverRunning.sendWebSocketSubscriptionMessage(
        WebSocketSubscriptionMessage(this, listOf(), value)
    )

context(serverRunning: ServerRunning) public suspend fun <A, T> WebSocketTopic<PathSpec1<A>, T>.send(
    path1: A,
    value: T
): Unit = serverRunning.sendWebSocketSubscriptionMessage(
    WebSocketSubscriptionMessage(this, listOf(path1), value)
)

context(serverRunning: ServerRunning) public suspend fun <A, B, T> WebSocketTopic<PathSpec2<A, B>, T>.send(
    path1: A,
    path2: B,
    value: T
): Unit = serverRunning.sendWebSocketSubscriptionMessage(
    WebSocketSubscriptionMessage(this, listOf(path1, path2), value)
)

context(serverRunning: ServerRunning) public suspend fun <A, B, C, T> WebSocketTopic<PathSpec3<A, B, C>, T>.send(
    path1: A,
    path2: B,
    path3: C,
    value: T
): Unit = serverRunning.sendWebSocketSubscriptionMessage(
    WebSocketSubscriptionMessage(this, listOf(path1, path2, path3), value)
)