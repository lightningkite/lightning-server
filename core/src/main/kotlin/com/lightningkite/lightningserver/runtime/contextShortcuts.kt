package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.PathSpec2
import com.lightningkite.lightningserver.pathing.PathSpec3
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.WebSocketTopic

/**
 * Sends a message to all WebSocket connections subscribed to this topic (no path parameters).
 *
 * @param value The message to send
 */
context(serverRuntime: ServerRuntime)
public suspend fun <T> WebSocketTopic<PathSpec0, T>.send(value: T): Unit =
    serverRuntime.sendWebSocketSubscriptionMessage(
        WebSocketSubscriptionMessage(this, value)
    )

/**
 * Sends a message to all WebSocket connections subscribed to this topic with one path parameter.
 *
 * @param path1 The first path parameter value
 * @param value The message to send
 */
context(serverRuntime: ServerRuntime)
public suspend fun <A, T> WebSocketTopic<PathSpec1<A>, T>.send(
    path1: A,
    value: T,
): Unit = serverRuntime.sendWebSocketSubscriptionMessage(
    WebSocketSubscriptionMessage(this, path1, value)
)

/**
 * Sends a message to all WebSocket connections subscribed to this topic with two path parameters.
 *
 * @param path1 The first path parameter value
 * @param path2 The second path parameter value
 * @param value The message to send
 */
context(serverRuntime: ServerRuntime)
public suspend fun <A, B, T> WebSocketTopic<PathSpec2<A, B>, T>.send(
    path1: A,
    path2: B,
    value: T,
): Unit = serverRuntime.sendWebSocketSubscriptionMessage(
    WebSocketSubscriptionMessage(this, path1, path2, value)
)

/**
 * Sends a message to all WebSocket connections subscribed to this topic with three path parameters.
 *
 * @param path1 The first path parameter value
 * @param path2 The second path parameter value
 * @param path3 The third path parameter value
 * @param value The message to send
 */
context(serverRuntime: ServerRuntime)
public suspend fun <A, B, C, T> WebSocketTopic<PathSpec3<A, B, C>, T>.send(
    path1: A,
    path2: B,
    path3: C,
    value: T,
): Unit = serverRuntime.sendWebSocketSubscriptionMessage(
    WebSocketSubscriptionMessage(this, path1, path2, path3, value)
)

/**
 * Queues a task for asynchronous execution, parented to the execution launching it.
 *
 * The task will be executed in the background. The exact execution mechanism depends
 * on the engine implementation.
 *
 * This takes a [ServerRuntime] rather than an [Engine] so that a launched task always has something
 * to be caused by: parentage across a queue is the one thing the serializable initiator exists for,
 * and a task launched from nowhere could not be joined back to the work that wanted it.
 *
 * @param input The input parameter for the task
 */
context(runtime: ServerRuntime)
public suspend operator fun <T> Task<T>.invoke(input: T): Unit =
    runtime.dispatchTask(this, input, runtime.execution)
