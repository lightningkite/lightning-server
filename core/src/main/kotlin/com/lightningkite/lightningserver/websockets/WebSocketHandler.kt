package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.LightningServerDsl
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.serialization.serializerOrContextual
import kotlinx.serialization.KSerializer

public interface WebSocketHandler<PATH: PathSpec, STORAGE> {
    public val storageSerializer: KSerializer<STORAGE>
    public context(serverRuntime: ServerRuntime) suspend fun willConnect(request: WebSocketConnectRequest<PATH>): STORAGE
    public context(connection: WebSocketConnection<PATH, STORAGE>) suspend fun didConnect()
    public context(connection: WebSocketConnection<PATH, STORAGE>) suspend fun messageFromClient(frame: WebSocketFrame)
    public context(connection: WebSocketConnection<PATH, STORAGE>) suspend fun messageFromSubscription(topic: WebSocketSubscriptionMessage<*, *>)
    public context(connection: WebSocketConnection<PATH, STORAGE>) suspend fun disconnect(reason: WebSocketClose)
}

// BUILDER

@InternalLightningServerApi
public suspend fun <PATH: PathSpec, STORAGE> WebSocketConnection<PATH, STORAGE>.didConnectNoOp(): Unit = Unit
@InternalLightningServerApi
public suspend fun <PATH: PathSpec, STORAGE> WebSocketConnection<PATH, STORAGE>.messageFromClientNoOp(frame: WebSocketFrame): Unit = Unit
@InternalLightningServerApi
public suspend fun <PATH: PathSpec, STORAGE> WebSocketConnection<PATH, STORAGE>.disconnectNoOp(reason: WebSocketClose): Unit = Unit

public inline fun <PATH: PathSpec, reified STORAGE> WebSocketHandler(
    storageSerializer: KSerializer<STORAGE> = serializerOrContextual<STORAGE>(),
    crossinline willConnect: suspend ServerRuntime.(request: WebSocketConnectRequest<PATH>) -> STORAGE,
    crossinline didConnect: suspend WebSocketConnection<PATH, STORAGE>.() -> Unit = WebSocketConnection<PATH, STORAGE>::didConnectNoOp,
    crossinline messageFromClient: suspend WebSocketConnection<PATH, STORAGE>.(frame: WebSocketFrame) -> Unit = WebSocketConnection<PATH, STORAGE>::messageFromClientNoOp,
    crossinline topicHandlers: TopicHandlersBuilder<PATH, STORAGE>.()->Unit = {},
    crossinline disconnect: suspend WebSocketConnection<PATH, STORAGE>.(reason: WebSocketClose) -> Unit = WebSocketConnection<PATH, STORAGE>::disconnectNoOp,
): WebSocketHandler<PATH, STORAGE> =
    object : WebSocketHandler<PATH, STORAGE> {
        override val storageSerializer: KSerializer<STORAGE> = storageSerializer
        override suspend context(serverRuntime: ServerRuntime) fun willConnect(request: WebSocketConnectRequest<PATH>): STORAGE = willConnect(contextOf(), request)
        override suspend context(connection: WebSocketConnection<PATH, STORAGE>) fun didConnect() {
            didConnect(contextOf(), )
        }
        override suspend context(connection: WebSocketConnection<PATH, STORAGE>) fun messageFromClient(frame: WebSocketFrame) {
            messageFromClient(contextOf(), frame)
        }
        private val subHandler = TopicHandlersBuilder<PATH, STORAGE>().apply(topicHandlers).build()
        override suspend context(connection: WebSocketConnection<PATH, STORAGE>) fun messageFromSubscription(topic: WebSocketSubscriptionMessage<*, *>) = subHandler(contextOf(), topic)
        override suspend context(connection: WebSocketConnection<PATH, STORAGE>) fun disconnect(reason: WebSocketClose) {
            disconnect(contextOf(), reason)
        }
    }

public class TopicHandlersBuilder<PATH: PathSpec, STORAGE>() {
    public var handler: suspend WebSocketConnection<PATH, STORAGE>.(topic: WebSocketSubscriptionMessage<*, *>) -> Unit = {}

    @LightningServerDsl
    @Suppress("UNCHECKED_CAST")
    public inline infix fun <TOPICPATH: PathSpec, T> WebSocketTopic<TOPICPATH, T>.bind(
        crossinline handler: suspend WebSocketConnection<PATH, STORAGE>.(topic: WebSocketSubscriptionMessage<TOPICPATH, T>) -> Unit
    ) {
        val topic = this
        this@TopicHandlersBuilder.handler = this@TopicHandlersBuilder.handler.let { current ->
            { it: WebSocketSubscriptionMessage<*, *> ->
                if (topic == it.topic) handler(it as WebSocketSubscriptionMessage<TOPICPATH, T>)
                else current(it)
            }
        }
    }

    public fun build(): suspend WebSocketConnection<PATH, STORAGE>.(topic: WebSocketSubscriptionMessage<*, *>) -> Unit = handler
}