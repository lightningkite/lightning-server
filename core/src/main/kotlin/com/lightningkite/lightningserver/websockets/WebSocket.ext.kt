package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.pathing.*

public fun <T> WebSocketTopic<PathSpec0, T>.request(): WebSocketSubscriptionRequest<PathSpec0, T> =
    WebSocketSubscriptionRequest(topic = this, rawPathArguments = emptyList())

public fun <T, A> WebSocketTopic<PathSpec1<A>, T>.request(path1: A): WebSocketSubscriptionRequest<PathSpec1<A>, T> =
    WebSocketSubscriptionRequest(topic = this, rawPathArguments = listOf(path1))

public fun <T, A, B> WebSocketTopic<PathSpec2<A, B>, T>.request(
    path1: A,
    path2: B,
): WebSocketSubscriptionRequest<PathSpec2<A, B>, T> =
    WebSocketSubscriptionRequest(topic = this, rawPathArguments = listOf(path1, path2))

public fun <T, A, B, C> WebSocketTopic<PathSpec3<A, B, C>, T>.request(
    path1: A,
    path2: B,
    path3: C,
): WebSocketSubscriptionRequest<PathSpec3<A, B, C>, T> =
    WebSocketSubscriptionRequest(topic = this, rawPathArguments = listOf(path1, path2, path3))

context(connection: WebSocketConnection<PATH, STORAGE>)
public suspend fun <PATH : PathSpec, STORAGE, T> subscribe(topic: WebSocketTopic<PathSpec0, T>): Unit =
    connection.subscribe(topic.request())

context(connection: WebSocketConnection<PATH, STORAGE>)
public suspend fun <PATH : PathSpec, STORAGE, T, A> subscribe(topic: WebSocketTopic<PathSpec1<A>, T>, path1: A): Unit =
    connection.subscribe(topic.request(path1))

context(connection: WebSocketConnection<PATH, STORAGE>)
public suspend fun <PATH : PathSpec, STORAGE, T, A, B> subscribe(
    topic: WebSocketTopic<PathSpec2<A, B>, T>,
    path1: A,
    path2: B,
): Unit = connection.subscribe(topic.request(path1, path2))

context(connection: WebSocketConnection<PATH, STORAGE>)
public suspend fun <PATH : PathSpec, STORAGE, T, A, B, C> subscribe(
    topic: WebSocketTopic<PathSpec3<A, B, C>, T>,
    path1: A,
    path2: B,
    path3: C,
): Unit = connection.subscribe(topic.request(path1, path2, path3))

context(connection: WebSocketConnection<PATH, STORAGE>)
public suspend fun <PATH : PathSpec, STORAGE, T> unsubscribe(topic: WebSocketTopic<PathSpec0, T>): Unit =
    connection.unsubscribe(topic.request())

context(connection: WebSocketConnection<PATH, STORAGE>)
public suspend fun <PATH : PathSpec, STORAGE, T, A> unsubscribe(
    topic: WebSocketTopic<PathSpec1<A>, T>,
    path1: A,
): Unit = connection.unsubscribe(topic.request(path1))

public suspend fun WebSocketConnection<*, *>.send(content: String): Unit = send(WebSocketFrame(content))
public suspend fun WebSocketConnection<*, *>.send(content: ByteArray): Unit = send(WebSocketFrame(content))