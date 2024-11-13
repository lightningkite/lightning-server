package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.pubsub.PubSub
import com.lightningkite.lightningserver.serialization.TypeRetriever
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

abstract class LocalMidWebsocket<STORAGE>(
    startingState: STORAGE,
    val handler: WebSocketHandler<STORAGE>,
    val path: ServerPath,
    val pubSub: PubSub,
    val scope: CoroutineScope
): MidWebsocket<STORAGE> {
    override var currentState: STORAGE = startingState
    override suspend fun repullState(): STORAGE = currentState
    override suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE) {
        currentState = modification(currentState)
    }
    override suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE {
        currentState = modification(currentState)
        return currentState
    }
    val subscriptions = HashMap<String, Job>()
    override suspend fun <T> subscribe(topic: WebSocketTopic<T>) {
        subscriptions[topic.topic]?.cancel()
        subscriptions[topic.topic] = scope.launch {
            pubSub.get(topic.topic, topic.type).collect { value ->
                Metrics.handlerPerformance(
                    WebSockets.HandlerSection(
                        path,
                        WebSockets.WsHandlerType.WSSUB
                    )
                ) {
                    handler.messageFromSubscription(this@LocalMidWebsocket, topic.topic, TypeRetriever{ value })
                }
            }
        }
        yield()
    }

    override suspend fun unsubscribe(topic: String) {
        subscriptions[topic]?.cancel()
    }
}