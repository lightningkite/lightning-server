@file:OptIn(InternalLightningServerApi::class, EngineApi::class)

package com.lightningkite.lightningserver.engine.local

import com.lightningkite.lightningserver.EngineApi
import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.messageFromSubscriptionAsRoot
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.pubsub.PubSubChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * A webSocket connection held open in this process, backed by pub/sub for topic subscriptions.
 *
 * Shared by every engine that owns a real socket. Subclasses supply only the transport: `send` to write
 * a frame and `close` to tear the socket down.
 */
@InternalLightningServerApi
public abstract class LocalWebSocketConnection<PATH : PathSpec, STORAGE>(
    startingState: STORAGE,
    override val request: WebSocketConnectRequest<PATH>,
    private val handler: WebSocketHandler<PATH, STORAGE>,
    private val scope: CoroutineScope,
    private val pubSub: (request: WebSocketSubscriptionRequest<*, Any?>) -> PubSubChannel<Any?>,
) : WebSocketConnection<PATH, STORAGE> {
    override var currentState: STORAGE = startingState

    context(server: ServerRuntime)
    override suspend fun repullState(): STORAGE = currentState

    context(server: ServerRuntime)
    override suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE) {
        currentState = modification(currentState)
    }

    context(server: ServerRuntime)
    override suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE {
        currentState = modification(currentState)
        return currentState
    }

    private val subscriptions = HashMap<WebSocketSubscriptionRequest<*, *>, Job>()

    /** The topic subscriptions this connection currently holds. */
    public val activeSubscriptions: Set<WebSocketSubscriptionRequest<*, *>> get() = subscriptions.keys.toSet()

    context(server: ServerRuntime)
    override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) {
        @Suppress("UNCHECKED_CAST")
        topic as WebSocketSubscriptionRequest<*, Any?>
        subscriptions.remove(topic)?.cancel()
        subscriptions[topic] = scope.launch {
            pubSub(topic).collect { value ->
                // Each delivery is caused by the published message, not by whatever called subscribe(), so it
                // runs as a root.
                handler.messageFromSubscriptionAsRoot(
                    this@LocalWebSocketConnection,
                    WebSocketSubscriptionMessage(topic, value),
                )
            }
            yield()
        }
    }

    context(server: ServerRuntime)
    override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) {
        subscriptions.remove(topic)?.cancel()
    }
}
