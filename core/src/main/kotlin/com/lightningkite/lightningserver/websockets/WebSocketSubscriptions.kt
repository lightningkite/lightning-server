package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.PathSpec2
import com.lightningkite.lightningserver.pathing.PathSpec3
import com.lightningkite.services.data.Unsafe

public fun <T> WebSocketSubscriptionRequest(
    topic: WebSocketTopic<PathSpec0, T>,
): WebSocketSubscriptionRequest<PathSpec0, T> =
    @OptIn(Unsafe::class) // SAFETY: The function signature ensures type safety
    WebSocketSubscriptionRequest.fromRawPathArguments(topic, emptyList())

public fun <T, A> WebSocketSubscriptionRequest(
    topic: WebSocketTopic<PathSpec1<A>, T>,
    first: A,
): WebSocketSubscriptionRequest<PathSpec1<A>, T> =
    @OptIn(Unsafe::class) // SAFETY: The function signature ensures type safety
    WebSocketSubscriptionRequest.fromRawPathArguments(topic, listOf(first))

public fun <T, A, B> WebSocketSubscriptionRequest(
    topic: WebSocketTopic<PathSpec2<A, B>, T>,
    first: A,
    second: B,
): WebSocketSubscriptionRequest<PathSpec2<A, B>, T> =
    @OptIn(Unsafe::class) // SAFETY: The function signature ensures type safety
    WebSocketSubscriptionRequest.fromRawPathArguments(topic, listOf(first, second))

public fun <T, A, B, C> WebSocketSubscriptionRequest(
    topic: WebSocketTopic<PathSpec3<A, B, C>, T>,
    first: A,
    second: B,
    third: C,
): WebSocketSubscriptionRequest<PathSpec3<A, B, C>, T> =
    @OptIn(Unsafe::class) // SAFETY: The function signature ensures type safety
    WebSocketSubscriptionRequest.fromRawPathArguments(topic, listOf(first, second, third))

/** The subscription that [message] is delivered to. */
public fun <PATH : PathSpec, T> WebSocketSubscriptionRequest(
    message: WebSocketSubscriptionMessage<PATH, T>,
): WebSocketSubscriptionRequest<PATH, T> =
    @OptIn(Unsafe::class) // SAFETY: The message already upholds the same invariants for the same topic
    WebSocketSubscriptionRequest.fromRawPathArguments(message.topic, message.rawPathArguments)

public fun <T> WebSocketSubscriptionMessage(
    topic: WebSocketTopic<PathSpec0, T>,
    value: T,
): WebSocketSubscriptionMessage<PathSpec0, T> =
    @OptIn(Unsafe::class) // SAFETY: The function signature ensures type safety
    WebSocketSubscriptionMessage.fromRawPathArguments(topic, emptyList(), value)

public fun <T, A> WebSocketSubscriptionMessage(
    topic: WebSocketTopic<PathSpec1<A>, T>,
    first: A,
    value: T,
): WebSocketSubscriptionMessage<PathSpec1<A>, T> =
    @OptIn(Unsafe::class) // SAFETY: The function signature ensures type safety
    WebSocketSubscriptionMessage.fromRawPathArguments(topic, listOf(first), value)

public fun <T, A, B> WebSocketSubscriptionMessage(
    topic: WebSocketTopic<PathSpec2<A, B>, T>,
    first: A,
    second: B,
    value: T,
): WebSocketSubscriptionMessage<PathSpec2<A, B>, T> =
    @OptIn(Unsafe::class) // SAFETY: The function signature ensures type safety
    WebSocketSubscriptionMessage.fromRawPathArguments(topic, listOf(first, second), value)

public fun <T, A, B, C> WebSocketSubscriptionMessage(
    topic: WebSocketTopic<PathSpec3<A, B, C>, T>,
    first: A,
    second: B,
    third: C,
    value: T,
): WebSocketSubscriptionMessage<PathSpec3<A, B, C>, T> =
    @OptIn(Unsafe::class) // SAFETY: The function signature ensures type safety
    WebSocketSubscriptionMessage.fromRawPathArguments(topic, listOf(first, second, third), value)

/** A message carrying [value] to every connection subscribed via [request]. */
public fun <PATH : PathSpec, T> WebSocketSubscriptionMessage(
    request: WebSocketSubscriptionRequest<PATH, T>,
    value: T,
): WebSocketSubscriptionMessage<PATH, T> =
    @OptIn(Unsafe::class) // SAFETY: The request already upholds the same invariants for the same topic
    WebSocketSubscriptionMessage.fromRawPathArguments(request.topic, request.rawPathArguments, value)
