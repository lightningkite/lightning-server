package com.lightningkite.lightningserver

import kotlinx.serialization.KSerializer

public interface WebSocketHandler<PATH: PathSpec, STORAGE> {
    public val storageSerializer: KSerializer<STORAGE>
    public suspend fun willConnect(serverRunning: ServerRunning, request: WebSocketConnectRequest<PATH>): STORAGE
    public suspend fun didConnect(connection: WebSocketConnection<PATH, STORAGE>)
    public suspend fun messageFromClient(connection: WebSocketConnection<PATH, STORAGE>, frame: WebSocketFrame)
    public suspend fun messageFromSubscription(connection: WebSocketConnection<PATH, STORAGE>, topic: WebSocketSubscriptionMessage<*, *>)
    public suspend fun disconnect(connection: WebSocketConnection<PATH, STORAGE>, reason: WebSocketClose)
}
@InternalLightningServerApi public suspend fun <PATH: PathSpec, STORAGE> WebSocketConnection<PATH, STORAGE>.didConnectNoOp(): Unit = Unit
@InternalLightningServerApi public suspend fun <PATH: PathSpec, STORAGE> WebSocketConnection<PATH, STORAGE>.messageFromClientNoOp(frame: WebSocketFrame): Unit = Unit
@InternalLightningServerApi public suspend fun <PATH: PathSpec, STORAGE> WebSocketConnection<PATH, STORAGE>.disconnectNoOp(reason: WebSocketClose): Unit = Unit

public inline fun <PATH: PathSpec, STORAGE> ServerDefinitionBuilder<*>.webSocketHandler(
    storageSerializer: KSerializer<STORAGE>,
    crossinline willConnect: suspend ServerRunning.(request: WebSocketConnectRequest<PATH>) -> STORAGE,
    crossinline didConnect: suspend WebSocketConnection<PATH, STORAGE>.() -> Unit = WebSocketConnection<PATH, STORAGE>::didConnectNoOp,
    crossinline messageFromClient: suspend WebSocketConnection<PATH, STORAGE>.(frame: WebSocketFrame) -> Unit = WebSocketConnection<PATH, STORAGE>::messageFromClientNoOp,
    crossinline topicHandlers: TopicHandlersBuilder<PATH, STORAGE>.()->Unit = {},
    crossinline disconnect: suspend WebSocketConnection<PATH, STORAGE>.(reason: WebSocketClose) -> Unit = WebSocketConnection<PATH, STORAGE>::disconnectNoOp,
): WebSocketHandler<PATH, STORAGE> =
    object : WebSocketHandler<PATH, STORAGE> {
        override val storageSerializer: KSerializer<STORAGE> = storageSerializer
        override suspend fun willConnect(serverRunning: ServerRunning, request: WebSocketConnectRequest<PATH>): STORAGE = willConnect(serverRunning, request)
        override suspend fun didConnect(connection: WebSocketConnection<PATH, STORAGE>) {
            didConnect(connection)
        }
        override suspend fun messageFromClient(connection: WebSocketConnection<PATH, STORAGE>, frame: WebSocketFrame) {
            messageFromClient(connection, frame)
        }
        private val subHandler = TopicHandlersBuilder<PATH, STORAGE>().apply(topicHandlers).build()
        override suspend fun messageFromSubscription(connection: WebSocketConnection<PATH, STORAGE>, topic: WebSocketSubscriptionMessage<*, *>) = subHandler(connection, topic)
        override suspend fun disconnect(connection: WebSocketConnection<PATH, STORAGE>, reason: WebSocketClose) {
            disconnect(connection, reason)
        }
    }

public class TopicHandlersBuilder<PATH: PathSpec, STORAGE>() {
    public var handler: suspend WebSocketConnection<PATH, STORAGE>.(topic: WebSocketSubscriptionMessage<*, *>) -> Unit = {}
    @Suppress("UNCHECKED_CAST")
    public inline infix fun <TOPICPATH: PathSpec, T> WebSocketTopic<TOPICPATH, T>.bind(crossinline handler: suspend WebSocketConnection<PATH, STORAGE>.(topic: WebSocketSubscriptionMessage<TOPICPATH, T>) -> Unit) {
        val topic = this
        this@TopicHandlersBuilder.handler = this@TopicHandlersBuilder.handler.let { current ->
            { it: WebSocketSubscriptionMessage<*, *> ->
                if(topic == it.topic) handler(it as WebSocketSubscriptionMessage<TOPICPATH, T>)
                else current(
                    it
                )
            }
        }
    }
    public fun build(): suspend WebSocketConnection<PATH, STORAGE>.(topic: WebSocketSubscriptionMessage<*, *>) -> Unit = handler
}