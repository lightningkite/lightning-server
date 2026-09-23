package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketTopic
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
context(server: Engine)
public operator fun <SERIALIZABLE, GOAL> ServerSetting<SERIALIZABLE, GOAL>.invoke(): GOAL =
    server.settings.get(this)

/**
 * Returns the current time according to the server's clock.
 *
 * Uses the configured clock from the server runtime, which can be overridden for testing.
 *
 * @return The current instant
 */
context(server: Engine)
public fun now(): Instant = server.clock.now()

/**
 * Provides access to the Engine instance from a context receiver.
 *
 * This allows nested functions to access the engine without explicit parameter passing.
 */
context(inContext: Engine)
public val engine: Engine get() = inContext

/**
 * Gets the location of an HTTP handler, or null if it's not registered.
 */
context(runner: Engine)
public val <P : PathSpec> HttpHandler<P>.locationOrNull: HttpEndpoint<P>? get() = runner.server.location(this)

/**
 * Gets the location of a WebSocket handler, or null if it's not registered.
 */
context(runner: Engine)
public val <P : PathSpec> WebSocketHandler<P, *>.locationOrNull: P? get() = runner.server.location(this)

/**
 * Gets the location of a WebSocket topic, or null if it's not registered.
 */
context(runner: Engine)
public val <P : PathSpec> WebSocketTopic<P, *>.locationOrNull: P? get() = runner.server.location(this)

/**
 * Gets the location of a task, or null if it's not registered.
 */
context(runner: Engine)
public val Task<*>.locationOrNull: PathSpec0? get() = runner.server.location(this)

/**
 * Gets the location of a startup task, or null if it's not registered.
 */
context(runner: Engine)
public val StartupTask.locationOrNull: PathSpec0? get() = runner.server.location(this)

/**
 * Gets the location of a scheduled task, or null if it's not registered.
 */
context(runner: Engine)
public val ScheduledTask.locationOrNull: PathSpec0? get() = runner.server.location(this)

/**
 * Gets the location of a server module, or null if it's not registered.
 */
context(runner: Engine)
public val ServerDefinition.locationOrNull: PathSpec0? get() = runner.server.location(this)

/**
 * Gets the location of a server module, or null if it's not registered.
 */
context(runner: Engine)
public val ServerBuilder.locationOrNull: PathSpec0? get() = runner.server.location(this)

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
context(runner: Engine)
public val <P : PathSpec> HttpHandler<P>.location: HttpEndpoint<P>
    get() = runner.server.location(this) ?: throw UnregisteredException(this)

/**
 * Gets the location of a WebSocket handler.
 *
 * @throws UnregisteredException if the handler is not registered with the server
 */
context(runner: Engine)
public val <P : PathSpec> WebSocketHandler<P, *>.location: P
    get() = runner.server.location(this) ?: throw UnregisteredException(this)

/**
 * Gets the location of a WebSocket topic.
 *
 * @throws UnregisteredException if the topic is not registered with the server
 */
context(runner: Engine)
public val <P : PathSpec> WebSocketTopic<P, *>.location: P
    get() = runner.server.location(this) ?: throw UnregisteredException(
        this
    )

/**
 * Gets the location of a task.
 *
 * @throws UnregisteredException if the task is not registered with the server
 */
context(runner: Engine)
public val Task<*>.location: PathSpec0 get() = runner.server.location(this) ?: throw UnregisteredException(this)

/**
 * Gets the location of a startup task.
 *
 * @throws UnregisteredException if the task is not registered with the server
 */
context(runner: Engine)
public val StartupTask.location: PathSpec0 get() = runner.server.location(this) ?: throw UnregisteredException(this)

/**
 * Gets the location of a pre-deploy task.
 *
 * @throws UnregisteredException if the task is not registered with the server
 */
context(runner: Engine)
public val PreDeployTask.location: PathSpec0 get() = runner.server.location(this) ?: throw UnregisteredException(this)

/**
 * Gets the location of a scheduled task.
 *
 * @throws UnregisteredException if the task is not registered with the server
 */
context(runner: Engine)
public val ScheduledTask.location: PathSpec0 get() = runner.server.location(this) ?: throw UnregisteredException(this)

/**
 * Gets the location of a server module.
 *
 * @throws UnregisteredException if the module is not registered with the server
 */
context(runner: Engine)
public val ServerDefinition.location: PathSpec0 get() = runner.server.location(this) ?: throw UnregisteredException(this)

/**
 * Gets the location of a server module.
 *
 * @throws UnregisteredException if the module is not registered with the server
 */
context(runner: Engine)
public val ServerBuilder.location: PathSpec0 get() = runner.server.location(this) ?: throw UnregisteredException(this)

/*
 * TODO: API Recommendations
 *
 * 1. UnregisteredException provides minimal context - just "Item $item is unregistered".
 *    Consider adding which server it was looked up in, or suggestions for common mistakes.
 */

