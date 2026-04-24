package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.definition.*
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.settings.ServerSettings
import com.lightningkite.lightningserver.websockets.DirectWebSocketSender
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.OpenTelemetry
import com.lightningkite.services.SettingContext
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Clock

/**
 * Core interface representing a running Lightning Server instance.
 *
 * ServerRuntime provides the execution context for a server, including:
 * - Access to the server definition (routes, handlers, settings)
 * - Serialization configuration for external (API) and internal (storage) use
 * - Settings management and access
 * - WebSocket subscription messaging
 * - Task execution
 * - Telemetry and clock access
 * - Reading settings
 *
 * Implementations of this interface are responsible for:
 * - Managing the server lifecycle
 * - Routing HTTP requests to appropriate handlers
 * - Executing scheduled and startup tasks
 * - Handling WebSocket connections and subscriptions
 *
 */
public interface ServerRuntime : SettingContext {
    /**
     * The server definition containing all routes, handlers, settings, and tasks.
     */
    public val server: ServerDefinition

    /**
     * Whether the different parts of the websocket handler (willConnect, didConnect, messageFromClient,
     * messageFromSubscription, disconnect) all occur in the same process.  If they do, you can use RAM to store
     * information between those events.
     */
    public val websocketHandlersRunOnSameMachine: Boolean get() = true

    /**
     * The public URL at which this server is accessible.
     *
     * Defaults to the value from general settings.
     */
    override val publicUrl: String
        get() = generalSettings().publicUrl

    /**
     * Serialization configuration for external API communication (HTTP bodies, etc.).
     */
    public val externalSerialization: Serialization

    /**
     * Serialization configuration for internal use (database storage, caching, etc.).
     */
    public val internalSerialization: Serialization

    /**
     * OpenTelemetry instance for distributed tracing and metrics.
     *
     * Currently returns null by default. Implementations should override this
     * to provide telemetry support.
     */
    override val openTelemetry: OpenTelemetry?
        get() = null // TODO

    /**
     * Clock used for time-based operations.
     *
     * Defaults to system clock but can be overridden for testing.
     */
    override val clock: Clock get() = Clock.System

    /**
     * Settings manager for accessing configured server settings.
     */
    public val settings: ServerSettings

    /**
     * Sends a WebSocket subscription message to all connections subscribed to the topic.
     *
     * The message will be delivered to all WebSocket connections that have subscribed
     * to the topic with matching path parameters.
     *
     * @param event The subscription message to send, including topic, path args, and value
     */
    public suspend fun <PATH : PathSpec, T> sendWebSocketSubscriptionMessage(event: WebSocketSubscriptionMessage<PATH, T>)

    /**
     * Optional direct WebSocket sender for engines that support it (e.g., AWS).
     *
     * When available, allows bypassing the pub/sub mechanism for direct message sending
     * to specific sockets. Used by [com.lightningkite.lightningserver.websockets.CoroutineWebsocketHandler]
     * to optimize message delivery.
     *
     * @return DirectWebSocketSender implementation if supported, null otherwise
     */
    public val directWebSocketSender: DirectWebSocketSender? get() = null

    /**
     * Invokes a task for asynchronous execution.
     *
     * The exact execution mechanism (background thread, coroutine, queue, etc.)
     * depends on the runtime implementation.
     *
     * @param input The input parameter for the task
     */
    public suspend fun <T> Task<T>.invoke(input: T)

    /**
     * Serializers module used for internal serialization.
     */
    override val internalSerializersModule: SerializersModule get() = internalSerialization.serializersModule

    /**
     * Unique identifier for this server instance.
     *
     * For single-machine engines, typically derived from network interface MAC address.
     * For serverless deployments, may be a Lambda function ID or similar.
     */
    public val serverId: String

    /**
     * Version identifier for the running server.
     *
     * May be "Unknown" if version information is not available.
     */
    public val serverVersion: String
}

/*
 * TODO: API Recommendations for ServerRuntime.kt
 *
 * 1. The openTelemetry property returns null by default with a TODO comment. This should either be
 *    implemented or the TODO removed if telemetry is truly optional. Document the expected behavior.
 *
 * 2. No lifecycle methods for server startup/shutdown. Consider adding:
 *    - suspend fun start()
 *    - suspend fun stop()
 *    - val isRunning: Boolean
 *
 * 3. The Task.invoke() method doesn't provide any feedback about task execution status or errors.
 *    Consider returning a result or providing a callback mechanism for task completion/failure.
 *
 * 4. sendWebSocketSubscriptionMessage doesn't document what happens if no connections are subscribed.
 *    Does it silently succeed? Document this behavior.
 *
 * 5. No way to query the server state (number of active connections, running tasks, etc.).
 *    Consider adding health/metrics accessors for observability.
 *
 * 6. The serverId and serverVersion have no validation. Consider validating that these are set
 *    properly during initialization or providing defaults.
 */

