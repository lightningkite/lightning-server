package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.websockets.*
import kotlin.time.Instant

/**
 * Retrieves the configured value for a server setting.
 *
 * This operator function allows calling a setting like a function to get its resolved value.
 *
 * Example:
 * ```kotlin
 * val database = setting("database", Database.Settings())
 * with(serverRuntime) {
 *     val db = database() // Gets the configured database instance
 * }
 * ```
 *
 * @return The configured value of this setting
 */
context(server: ServerRuntime)
public operator fun <SERIALIZABLE, GOAL> ServerSetting<SERIALIZABLE, GOAL>.invoke(): GOAL =
    server.settings.get(this)

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
 * Queues a task for asynchronous execution.
 *
 * The task will be executed in the background. The exact execution mechanism depends
 * on the engine implementation (e.g., GlobalScope.launch for single-machine engines).
 *
 * @param input The input parameter for the task
 */
context(serverRuntime: ServerRuntime)
public suspend operator fun <T> Task<T>.invoke(input: T): Unit =
    with(serverRuntime) {
        this@invoke.invoke(input)
    }

/**
 * Returns the current time according to the server's clock.
 *
 * Uses the configured clock from the server runtime, which can be overridden for testing.
 *
 * @return The current instant
 */
context(server: ServerRuntime)
public fun now(): Instant = server.clock.now()

/**
 * Provides access to the ServerRuntime instance from a context receiver.
 *
 * This allows nested functions to access the runtime without explicit parameter passing.
 */
context(runner: ServerRuntime)
public val serverRuntime: ServerRuntime get() = runner

/**
 * Gets the location of an HTTP handler, or null if it's not registered.
 */
public context(runner: ServerRuntime)
val <P : PathSpec> HttpHandler<P>.locationOrNull: HttpEndpoint<P>? get() = runner.server.location(this)

/**
 * Gets the location of a WebSocket handler, or null if it's not registered.
 */
public context(runner: ServerRuntime)
val <P : PathSpec> WebSocketHandler<P, *>.locationOrNull: P? get() = runner.server.location(this)

/**
 * Gets the location of a WebSocket topic, or null if it's not registered.
 */
public context(runner: ServerRuntime)
val <P : PathSpec> WebSocketTopic<P, *>.locationOrNull: P? get() = runner.server.location(this)

/**
 * Gets the location of a task, or null if it's not registered.
 */
public context(runner: ServerRuntime)
val Task<*>.locationOrNull: PathSpec0? get() = runner.server.location(this)

/**
 * Gets the location of a startup task, or null if it's not registered.
 */
public context(runner: ServerRuntime)
val StartupTask.locationOrNull: PathSpec0? get() = runner.server.location(this)

/**
 * Gets the location of a scheduled task, or null if it's not registered.
 */
public context(runner: ServerRuntime)
val ScheduledTask.locationOrNull: PathSpec0? get() = runner.server.location(this)

/**
 * Gets the location of a server module, or null if it's not registered.
 */
public context(runner: ServerRuntime)
val ServerDefinition.locationOrNull: PathSpec0? get() = runner.server.location(this)

/**
 * Gets the location of a server module, or null if it's not registered.
 */
public context(runner: ServerRuntime)
val ServerBuilder.locationOrNull: PathSpec0? get() = runner.server.location(this)

/**
 * Exception thrown when attempting to get the location of an unregistered item.
 *
 * This indicates that a handler, task, or topic was not included in the server definition
 * via the bind operator or similar registration mechanism.
 */
public class UnregisteredException internal constructor(item: Any) :
    IllegalStateException("Item $item is unregistered and has no location")

/**
 * Gets the location of an HTTP handler.
 *
 * @throws UnregisteredException if the handler is not registered with the server
 */
public context(runner: ServerRuntime)
val <P : PathSpec> HttpHandler<P>.location: HttpEndpoint<P>
    get() = runner.server.location(this) ?: throw UnregisteredException(this)

/**
 * Gets the location of a WebSocket handler.
 *
 * @throws UnregisteredException if the handler is not registered with the server
 */
public context(runner: ServerRuntime)
val <P : PathSpec> WebSocketHandler<P, *>.location: P
    get() = runner.server.location(this) ?: throw UnregisteredException(this)

/**
 * Gets the location of a WebSocket topic.
 *
 * @throws UnregisteredException if the topic is not registered with the server
 */
public context(runner: ServerRuntime)
val <P : PathSpec> WebSocketTopic<P, *>.location: P
    get() = runner.server.location(this) ?: throw UnregisteredException(
        this
    )

/**
 * Gets the location of a task.
 *
 * @throws UnregisteredException if the task is not registered with the server
 */
public context(runner: ServerRuntime)
val Task<*>.location: PathSpec0 get() = runner.server.location(this) ?: throw UnregisteredException(this)

/**
 * Gets the location of a startup task.
 *
 * @throws UnregisteredException if the task is not registered with the server
 */
public context(runner: ServerRuntime)
val StartupTask.location: PathSpec0 get() = runner.server.location(this) ?: throw UnregisteredException(this)

/**
 * Gets the location of a scheduled task.
 *
 * @throws UnregisteredException if the task is not registered with the server
 */
public context(runner: ServerRuntime)
val ScheduledTask.location: PathSpec0 get() = runner.server.location(this) ?: throw UnregisteredException(this)

/**
 * Gets the location of a server module.
 *
 * @throws UnregisteredException if the module is not registered with the server
 */
public context(runner: ServerRuntime)
val ServerDefinition.location: PathSpec0 get() = runner.server.location(this) ?: throw UnregisteredException(this)

/**
 * Gets the location of a server module.
 *
 * @throws UnregisteredException if the module is not registered with the server
 */
public context(runner: ServerRuntime)
val ServerBuilder.location: PathSpec0 get() = runner.server.location(this) ?: throw UnregisteredException(this)

