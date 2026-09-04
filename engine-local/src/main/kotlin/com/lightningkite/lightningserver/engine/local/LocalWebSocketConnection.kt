package com.lightningkite.lightningserver.engine.local

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.path
import com.lightningkite.lightningserver.runtime.Initiator
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.runtime.forExecution
import com.lightningkite.lightningserver.runtime.phase
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
    /** The socket's connect initiator, so a delivery can name the socket it is being delivered to. */
    public val connectInitiator: Initiator.WebSocket,
    private val handler: WebSocketHandler<PATH, STORAGE>,
    private val scope: CoroutineScope,
    /** Needed to deliver a subscription message, which is a fresh execution rather than part of one. */
    private val server: Engine,
    private val pubSub: (request: WebSocketSubscriptionRequest<*, Any?>) -> PubSubChannel<Any?>,
) : WebSocketConnection<PATH, STORAGE> {

    override var currentState: STORAGE = startingState
    override suspend fun repullState(): STORAGE = currentState
    override suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE) {
        currentState = modification(currentState)
    }

    override suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE {
        currentState = modification(currentState)
        return currentState
    }

    private val subscriptions = HashMap<WebSocketSubscriptionRequest<*, *>, Job>()

    /** The topic subscriptions this connection currently holds. */
    public val activeSubscriptions: Set<WebSocketSubscriptionRequest<*, *>> get() = subscriptions.keys.toSet()

    override suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>) {
        @Suppress("UNCHECKED_CAST")
        topic as WebSocketSubscriptionRequest<*, Any?>
        subscriptions.remove(topic)?.cancel()
        subscriptions[topic] = scope.launch {
            pubSub(topic).collect { value ->
                with(server.forExecution(with(server) { connectInitiator.phase(Initiator.WebSocket.Phase.SubscriptionMessage) })) {
                    handler.messageFromSubscription(
                        this@LocalWebSocketConnection,
                        WebSocketSubscriptionMessage(topic.topic, topic.pathInContext.rawPathArguments, value),
                    )
                }
            }
            yield()
        }
    }

    override suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>) {
        subscriptions.remove(topic)?.cancel()
    }
}
