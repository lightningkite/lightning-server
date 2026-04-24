@file:OptIn(InternalLightningServerApi::class)

package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.LightningServerDsl
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.KSerializer

public interface WebSocketHandler<PATH : PathSpec, STORAGE> {
    public val storageSerializer: KSerializer<STORAGE>
    public context(serverRuntime: ServerRuntime)
    suspend fun willConnect(request: WebSocketConnectRequest<PATH>): STORAGE
    public context(connection: WebSocketConnection<PATH, STORAGE>)
    suspend fun didConnect()
    public context(connection: WebSocketConnection<PATH, STORAGE>)
    suspend fun messageFromClient(frame: WebSocketFrame)
    public context(connection: WebSocketConnection<PATH, STORAGE>)
    suspend fun messageFromSubscription(topic: WebSocketSubscriptionMessage<*, *>)
    public context(connection: WebSocketConnection<PATH, STORAGE>)
    suspend fun disconnect(reason: WebSocketClose)
}

/**
 * Marker interface for WebSocket handlers that can be executed directly
 * without pub/sub overhead in local (single-process) engines.
 *
 * When a handler implements this interface, local engines (Ktor, Netty, JDK)
 * can call [handleDirect] instead of going through the standard lifecycle
 * (willConnect → task → pub/sub → messageFromClient).
 *
 * This is particularly useful for [CoroutineWebsocketHandler] which normally
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

@InternalLightningServerApi
public suspend fun <PATH : PathSpec, STORAGE> WebSocketConnection<PATH, STORAGE>.didConnectNoOp(): Unit = Unit

@InternalLightningServerApi
public suspend fun <PATH : PathSpec, STORAGE> WebSocketConnection<PATH, STORAGE>.messageFromClientNoOp(frame: WebSocketFrame): Unit =
    Unit

@InternalLightningServerApi
public suspend fun <PATH : PathSpec, STORAGE> WebSocketConnection<PATH, STORAGE>.disconnectNoOp(reason: WebSocketClose): Unit =
    Unit

public inline fun <PATH : PathSpec, reified STORAGE> WebSocketHandler(
    storageSerializer: KSerializer<STORAGE> = serializerOrContextual<STORAGE>(),
    crossinline willConnect: suspend ServerRuntime.(request: WebSocketConnectRequest<PATH>) -> STORAGE,
    crossinline didConnect: suspend WebSocketConnection<PATH, STORAGE>.() -> Unit = WebSocketConnection<PATH, STORAGE>::didConnectNoOp,
    crossinline messageFromClient: suspend WebSocketConnection<PATH, STORAGE>.(frame: WebSocketFrame) -> Unit = WebSocketConnection<PATH, STORAGE>::messageFromClientNoOp,
    crossinline topicHandlers: TopicHandlersBuilder<PATH, STORAGE>.() -> Unit = {},
    crossinline disconnect: suspend WebSocketConnection<PATH, STORAGE>.(reason: WebSocketClose) -> Unit = WebSocketConnection<PATH, STORAGE>::disconnectNoOp,
): WebSocketHandler<PATH, STORAGE> =
    object : WebSocketHandler<PATH, STORAGE> {
        override val storageSerializer: KSerializer<STORAGE> = storageSerializer
        override suspend context(serverRuntime: ServerRuntime)
        fun willConnect(request: WebSocketConnectRequest<PATH>): STORAGE =
            willConnect(contextOf<ServerRuntime>(), request)

        override suspend context(connection: WebSocketConnection<PATH, STORAGE>)
        fun didConnect() {
            didConnect(contextOf<WebSocketConnection<PATH, STORAGE>>())
        }

        override suspend context(connection: WebSocketConnection<PATH, STORAGE>)
        fun messageFromClient(frame: WebSocketFrame) {
            messageFromClient(contextOf<WebSocketConnection<PATH, STORAGE>>(), frame)
        }

        private val subHandler = TopicHandlersBuilder<PATH, STORAGE>().apply(topicHandlers).build()
        override suspend context(connection: WebSocketConnection<PATH, STORAGE>)
        fun messageFromSubscription(topic: WebSocketSubscriptionMessage<*, *>) =
            subHandler(contextOf<WebSocketConnection<PATH, STORAGE>>(), topic)

        override suspend context(connection: WebSocketConnection<PATH, STORAGE>)
        fun disconnect(reason: WebSocketClose) {
            disconnect(contextOf<WebSocketConnection<PATH, STORAGE>>(), reason)
        }
    }

public class TopicHandlersBuilder<PATH : PathSpec, STORAGE>() {
    public var handler: suspend WebSocketConnection<PATH, STORAGE>.(topic: WebSocketSubscriptionMessage<*, *>) -> Unit =
        {}

    @Suppress("UNCHECKED_CAST", "DSL_MARKER_APPLIED_TO_WRONG_TARGET")
    @LightningServerDsl
    public inline infix fun <TOPICPATH : PathSpec, T> WebSocketTopic<TOPICPATH, T>.bind(
        crossinline handler: suspend WebSocketConnection<PATH, STORAGE>.(topic: WebSocketSubscriptionMessage<TOPICPATH, T>) -> Unit,
    ) {
        val topic = this
        this@TopicHandlersBuilder.handler = this@TopicHandlersBuilder.handler.let { current ->
            { it: WebSocketSubscriptionMessage<*, *> ->
                if (topic == it.topic) handler(it as WebSocketSubscriptionMessage<TOPICPATH, T>)
                else current(it)
            }
        }
    }

    public fun build(): suspend WebSocketConnection<PATH, STORAGE>.(topic: WebSocketSubscriptionMessage<*, *>) -> Unit =
        handler
}