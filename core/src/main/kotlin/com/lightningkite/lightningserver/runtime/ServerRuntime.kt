package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.PathSpec2
import com.lightningkite.lightningserver.pathing.PathSpec3
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.WebSocketTopic

/**
 * An [Engine], running one execution on behalf of an [initiator].
 *
 * An "execution" is one run of anything the server can run: an HTTP request, one WebSocket lifecycle
 * phase, a task, a schedule tick, a startup task, or a pre-deploy task. Everything an execution needs
 * beyond its attribution — settings, serialization, telemetry, task dispatch — is process-wide, so a
 * runtime is an engine plus one extra property.
 *
 * ## Why this is a type rather than a convention
 * Declaring `context(server: ServerRuntime)` says the work being done is attributable, and declaring
 * `context(engine: Engine)` says it is not. That makes "who initiated this?" a question the compiler
 * answers: work that must be audited cannot accidentally be written somewhere no initiator exists.
 *
 * Runtimes are minted by the framework at the seam every engine funnels through
 * ([com.lightningkite.lightningserver.runtime.forExecution]), never by user code.
 */
public interface ServerRuntime : Engine {
    /**
     * What started the execution this runtime is serving.
     *
     * ## Why it lives here rather than being passed as a parameter
     * Not a stylistic choice, and not a candidate for "simplification" into an argument. The sites
     * that must attribute their work are the two logical interceptors *and the inside of a handler
     * body*: disclosure auditing hangs off `emitTypedOutput`, which is called from within
     * `ApiHttpHandler` and `ApiWebSocketHandler`. Reaching that by parameter would mean adding one to
     * `HttpHandler.handle`, and so to every endpoint handler in the framework and in user code. The
     * runtime context is the only carrier already threaded to all three.
     */
    public val initiator: Initiator
}

/**
 * Sends a message to all WebSocket connections subscribed to this topic (no path parameters).
 *
 * @param value The message to send
 */
context(serverRuntime: ServerRuntime)
public suspend fun <T> WebSocketTopic<PathSpec0, T>.send(value: T): Unit =
    serverRuntime.sendWebSocketSubscriptionMessage(
        WebSocketSubscriptionMessage(this, listOf(), value)
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
    WebSocketSubscriptionMessage(this, listOf(path1), value)
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
    WebSocketSubscriptionMessage(this, listOf(path1, path2), value)
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
    WebSocketSubscriptionMessage(this, listOf(path1, path2, path3), value)
)

/**
 * Queues a task for asynchronous execution, parented to the execution launching it.
 *
 * The task will be executed in the background. The exact execution mechanism depends
 * on the engine implementation (e.g., GlobalScope.launch for single-machine engines).
 *
 * This takes a [ServerRuntime] rather than an [Engine] so that a launched task always has something
 * to be caused by: parentage across a queue is the one thing the serializable initiator exists for,
 * and a task launched from nowhere could not be joined back to the work that wanted it.
 *
 * @param input The input parameter for the task
 */
@OptIn(InternalLightningServerApi::class)
context(serverRuntime: ServerRuntime)
public suspend operator fun <T> Task<T>.invoke(input: T): Unit =
    with(serverRuntime) {
        this@invoke.invoke(input, serverRuntime.initiator.cause)
    }

/**
 * Provides access to the ServerRuntime instance from a context receiver.
 *
 * This allows nested functions to access the runtime without explicit parameter passing.
 */
context(runner: ServerRuntime)
public val serverRuntime: ServerRuntime get() = runner
