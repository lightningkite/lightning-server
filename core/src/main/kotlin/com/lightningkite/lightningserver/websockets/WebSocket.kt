package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.location
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable


/**
 * A named topic that WebSocket connections can subscribe to for receiving pub/sub messages.
 *
 * Topics enable server-to-client push notifications. When the server publishes a message to a topic,
 * all WebSocket connections subscribed to that topic receive the message.
 *
 * Topics can have path parameters (e.g., `/users/{userId}/notifications`) allowing subscriptions
 * to be scoped to specific resources.
 *
 * @param PATH The PathSpec type defining any path parameters for this topic
 * @param T The type of messages published to this topic
 * @property type The serializer for message type T
 */
public class WebSocketTopic<PATH : PathSpec, T> internal constructor(
    public val type: KSerializer<T>,
)

/**
 * A request from a WebSocket client to subscribe to a topic.
 *
 * Contains the topic to subscribe to and any path parameter values needed to resolve the subscription.
 *
 * @param PATH The PathSpec type for the topic
 * @param T The message type for the topic
 * @property topic The topic to subscribe to
 * @property rawPathArguments The path parameter values for this subscription
 */
public data class WebSocketSubscriptionRequest<PATH : PathSpec, T>(
    val topic: WebSocketTopic<PATH, T>,
    val rawPathArguments: List<Any?>,
) : HasContextualPath<PATH> {
    context(server: ServerRuntime)
    override val pathInContext: ResolvedPath<PATH> get() = ResolvedPath(topic.location, rawPathArguments)
}

/**
 * A message published to a WebSocket topic.
 *
 * When the server sends a message to a topic, it's wrapped in this structure along with
 * the path parameters that identify which subscription instances should receive it.
 *
 * @param PATH The PathSpec type for the topic
 * @param T The message type
 * @property topic The topic this message is for
 * @property rawPathArguments The path parameters identifying which subscriptions to notify
 * @property value The actual message payload
 */
public data class WebSocketSubscriptionMessage<PATH : PathSpec, T>(
    val topic: WebSocketTopic<PATH, T>,
    val rawPathArguments: List<Any?>,
    val value: T,
) : HasContextualPath<PATH> {
    context(server: ServerRuntime)
    override val pathInContext: ResolvedPath<PATH> get() = ResolvedPath(topic.location, rawPathArguments)
}


/**
 * The initial WebSocket connection request containing connection metadata.
 *
 * Similar to [com.lightningkite.lightningserver.http.HttpRequest] but for WebSocket connections.
 * Contains the matched path, headers, query parameters, and other connection details.
 *
 * @param PATH The PathSpec type for this WebSocket endpoint
 */
@Serializable
public data class WebSocketConnectRequest<PATH : PathSpec>(
    override val path: RawWebSocketPath<PATH>,
    override val queryParameters: QueryParameters = QueryParameters.EMPTY,
    override val headers: HttpHeaders = HttpHeaders.EMPTY,
    override val domain: String = "",
    override val protocol: String = "",
    override val sourceIp: String = "",
    override val upstreamRequestId: String? = null,
    override val cache: SerializableCache = SerializableCache(),
    /**
     * Engine-specific socket identifier for direct message sending.
     * For AWS API Gateway, this is the connection ID.
     * Used by CoroutineWebSocketHandler to bypass pub/sub for direct sends.
     */
    val engineSocketId: String? = null,
) : Request<PATH>() {
    /** The gateway's identifier for a socket is the socket itself, so there is nothing else to hold. */
    override val engineRequestId: String? get() = engineSocketId

    /**
     * Derives a logical sub-connection of this one, as multiplexing carries several logical sockets
     * over a single physical connection.
     *
     * Use this only for a genuinely distinct logical socket, with
     * [com.lightningkite.lightningserver.runtime.subConnection] deriving the initiator that gives it
     * its own socket identity. A shim that merely rewrites the path of the same socket is not opening
     * one, and should use [com.lightningkite.lightningserver.runtime.rewritePath] instead.
     */
    public fun <PATH2 : PathSpec> subConnection(
        path: RawWebSocketPath<PATH2>,
        queryParameters: QueryParameters = this.queryParameters,
    ): WebSocketConnectRequest<PATH2> = WebSocketConnectRequest(
        path = path,
        queryParameters = queryParameters,
        headers = headers,
        domain = domain,
        protocol = protocol,
        sourceIp = sourceIp,
        upstreamRequestId = upstreamRequestId,
        cache = cache,
        engineSocketId = engineSocketId,
    )
}

/**
 * Represents an active WebSocket connection with stateful lifecycle management.
 *
 * This is the socket alone: connection state, subscriptions, and messaging. The server it runs on is
 * a separate concern, supplied to every [WebSocketHandler] method as a [ServerRuntime] context.
 *
 * **State Management:**
 * Each connection maintains a STORAGE object that can be updated in two ways:
 * - [updateStateImmediately]: Atomically updates state and returns the new value
 * - [queueStateUpdate]: Queues an update to be applied later (useful for high-frequency updates)
 *
 * **Subscriptions:**
 * Connections can [subscribe] and [unsubscribe] from topics to receive pub/sub messages.
 *
 * **Messaging:**
 * - [send]: Send a frame to the client
 * - [close]: Close the connection with a reason code
 *
 * @param PATH The PathSpec type for this WebSocket endpoint
 * @param STORAGE The type of state object maintained for this connection
 */
public interface WebSocketConnection<PATH : PathSpec, STORAGE> {
    /** The original connection request */
    public val request: WebSocketConnectRequest<PATH>

    /** The current state for this connection */
    public val currentState: STORAGE

    /**
     * Reloads the state from persistent storage.
     * Useful if the state might have been modified externally.
     */
    public suspend fun repullState(): STORAGE

    /**
     * Queues a state modification to be applied asynchronously.
     *
     * Use this for high-frequency updates where immediate consistency isn't required.
     */
    public suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE)

    /**
     * Immediately applies a state modification and returns the new state.
     *
     * This operation is atomic - the modification function is applied to the current
     * state and the result becomes the new state.
     */
    public suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE

    /**
     * Subscribes this connection to a topic to receive future messages.
     */
    public suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>)

    /**
     * Unsubscribes this connection from a topic.
     */
    public suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>)

    /**
     * Sends a frame to the connected client.
     */
    public suspend fun send(frame: WebSocketFrame)

    /**
     * Closes the WebSocket connection with a reason code.
     */
    public suspend fun close(reason: WebSocketClose)
}

/*
 * TODO: API Recommendations for WebSocket.kt
 *
 * 1. WebSocketTopic constructor is internal but there's no public factory method.
 *    How do users create topics? Document the intended creation mechanism.
 *
 * 2. The queueStateUpdate() function has no documentation on when queued updates are applied,
 *    ordering guarantees, or what happens if the connection closes before updates are applied.
 *
 * 3. No way to query current subscriptions for a connection. Adding a `val subscriptions: Set<WebSocketSubscriptionRequest<*, *>>`
 *    would be useful for debugging and state inspection.
 *
 * 4. The subscribe/unsubscribe operations don't return success/failure. If a topic doesn't exist or
 *    subscription fails, how does the caller know? Consider returning Boolean or throwing exceptions.
 *
 * 5. No ping/pong support exposed in the API. WebSocket implementations typically need this for
 *    connection keepalive. Consider adding automatic ping or exposing manual ping control.
 *
 * 6. The currentState property could be stale if queueStateUpdate is used. Document the
 *    consistency model (eventual consistency? last-write-wins?).
 */


