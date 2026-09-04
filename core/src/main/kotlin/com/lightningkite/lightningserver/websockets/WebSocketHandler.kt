@file:OptIn(InternalLightningServerApi::class)

package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.LightningServerDsl
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.KSerializer

/**
 * The five phases of a WebSocket's life, as the server sees them.
 *
 * The server is the context and the socket is an argument, because they have different lifetimes: on
 * a serverless engine each phase below is a separate invocation with its own runtime, while the
 * connection is the one thing that persists across all of them.
 */
public interface WebSocketHandler<PATH : PathSpec, STORAGE> {
    public val storageSerializer: KSerializer<STORAGE>
    public context(serverRuntime: ServerRuntime)
    suspend fun willConnect(request: WebSocketConnectRequest<PATH>): STORAGE
    public context(serverRuntime: ServerRuntime)
    suspend fun didConnect(connection: WebSocketConnection<PATH, STORAGE>)
    public context(serverRuntime: ServerRuntime)
    suspend fun messageFromClient(connection: WebSocketConnection<PATH, STORAGE>, frame: WebSocketFrame)
    public context(serverRuntime: ServerRuntime)
    suspend fun messageFromSubscription(
        connection: WebSocketConnection<PATH, STORAGE>,
        topic: WebSocketSubscriptionMessage<*, *>,
    )
    public context(serverRuntime: ServerRuntime)
    suspend fun disconnect(connection: WebSocketConnection<PATH, STORAGE>, reason: WebSocketClose)
}

/**
 * Marker interface for WebSocket handlers that can be executed directly
 * without pub/sub overhead in local (single-process) engines.
 *
 * When a handler implements this interface, local engines (Ktor, Netty, JDK)
 * can call [handleDirect] instead of going through the standard lifecycle
 * (willConnect → task → pub/sub → messageFromClient).
 *
 * This is particularly useful for [CoroutineWebSocketHandler] which normally
 * uses pub/sub channels to communicate between the connection and background task.
 * In local engines, this overhead is unnecessary since everything runs in-process.
 */
public interface DirectExecutableWebSocketHandler<PATH : PathSpec> {
    /**
     * Handle the WebSocket connection directly without pub/sub.
     *
     * Local engines should call this instead of the standard lifecycle when available.
     * The implementation should run the entire WebSocket session in this coroutine.
     *
     * @param serverRuntime The server runtime context
     * @param request The initial connection request
     * @param incoming Receive channel of frames from the client (closes when client disconnects)
     * @param send Function to send frames to the client
     * @param close Function to close the connection with a reason
     */
    public suspend fun handleDirect(
        serverRuntime: ServerRuntime,
        request: WebSocketConnectRequest<PATH>,
        incoming: ReceiveChannel<WebSocketFrame>,
        send: suspend (WebSocketFrame) -> Unit,
        close: suspend (WebSocketClose) -> Unit,
    )
}

// BUILDER

/**
 * Builds a handler from one lambda per lifecycle phase.
 *
 * The connection is the lambdas' receiver and the [ServerRuntime] their context, which is the
 * opposite of how [WebSocketHandler] declares them. A socket body spends most of its lines on the
 * socket — `send`, `currentState`, `subscribe` — so that is what `this` should be, while the runtime
 * is what the settings and service accessors want and they take it as a context anyway.
 */
public inline fun <PATH : PathSpec, reified STORAGE> WebSocketHandler(
    storageSerializer: KSerializer<STORAGE> = serializerOrContextual<STORAGE>(),
    crossinline willConnect: suspend ServerRuntime.(request: WebSocketConnectRequest<PATH>) -> STORAGE,
    crossinline didConnect: suspend context(ServerRuntime) WebSocketConnection<PATH, STORAGE>.() -> Unit = {},
    crossinline messageFromClient: suspend context(ServerRuntime) WebSocketConnection<PATH, STORAGE>.(frame: WebSocketFrame) -> Unit = {},
    crossinline topicHandlers: TopicHandlersBuilder<PATH, STORAGE>.() -> Unit = {},
    crossinline disconnect: suspend context(ServerRuntime) WebSocketConnection<PATH, STORAGE>.(reason: WebSocketClose) -> Unit = {},
): WebSocketHandler<PATH, STORAGE> =
    object : WebSocketHandler<PATH, STORAGE> {
        override val storageSerializer: KSerializer<STORAGE> = storageSerializer
        override suspend context(serverRuntime: ServerRuntime)
        fun willConnect(request: WebSocketConnectRequest<PATH>): STORAGE =
            willConnect(serverRuntime, request)

        override suspend context(serverRuntime: ServerRuntime)
        fun didConnect(connection: WebSocketConnection<PATH, STORAGE>) {
            didConnect(serverRuntime, connection)
        }

        override suspend context(serverRuntime: ServerRuntime)
        fun messageFromClient(connection: WebSocketConnection<PATH, STORAGE>, frame: WebSocketFrame) {
            messageFromClient(serverRuntime, connection, frame)
        }

        private val subHandler = TopicHandlersBuilder<PATH, STORAGE>().apply(topicHandlers).build()
        override suspend context(serverRuntime: ServerRuntime)
        fun messageFromSubscription(
            connection: WebSocketConnection<PATH, STORAGE>,
            topic: WebSocketSubscriptionMessage<*, *>,
        ): Unit = subHandler(serverRuntime, connection, topic)

        override suspend context(serverRuntime: ServerRuntime)
        fun disconnect(connection: WebSocketConnection<PATH, STORAGE>, reason: WebSocketClose) {
            disconnect(serverRuntime, connection, reason)
        }
    }

public class TopicHandlersBuilder<PATH : PathSpec, STORAGE>() {
    public var handler: suspend context(ServerRuntime) WebSocketConnection<PATH, STORAGE>.(topic: WebSocketSubscriptionMessage<*, *>) -> Unit =
        {}

    @Suppress("UNCHECKED_CAST", "DSL_MARKER_APPLIED_TO_WRONG_TARGET")
    @LightningServerDsl
    public inline infix fun <TOPICPATH : PathSpec, T> WebSocketTopic<TOPICPATH, T>.bind(
        crossinline handler: suspend context(ServerRuntime) WebSocketConnection<PATH, STORAGE>.(topic: WebSocketSubscriptionMessage<TOPICPATH, T>) -> Unit,
    ) {
        val topic = this
        this@TopicHandlersBuilder.handler = this@TopicHandlersBuilder.handler.let { current ->
            { it: WebSocketSubscriptionMessage<*, *> ->
                if (topic == it.topic) handler(it as WebSocketSubscriptionMessage<TOPICPATH, T>)
                else current(it)
            }
        }
    }

    public fun build(): suspend context(ServerRuntime) WebSocketConnection<PATH, STORAGE>.(topic: WebSocketSubscriptionMessage<*, *>) -> Unit =
        handler
}
