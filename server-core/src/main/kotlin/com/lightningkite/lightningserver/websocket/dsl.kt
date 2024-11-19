package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.serialization.TypeRetriever
import com.lightningkite.serialization.serializerOrContextual
import kotlinx.serialization.KSerializer

@LightningServerDsl
inline fun <reified STORAGE> ServerPath.websocket(
    noinline willConnect: suspend (WebSocketConnectRequest) -> STORAGE,
    noinline didConnect: suspend WebSocketConnection<STORAGE>.() -> Unit = { },
    noinline message: suspend WebSocketConnection<STORAGE>.(WebSocketFrame) -> Unit = { },
    noinline subscription: suspend WebSocketConnection<STORAGE>.(topic: String, retriever: TypeRetriever) -> Unit = { _, _ -> },
    noinline disconnect: suspend WebSocketConnection<STORAGE>.(WebSocketClose) -> Unit = {}
): ServerPath {
    WebSockets.handlers[this] = object : WebSocketHandler<STORAGE> {
        override val storageSerializer: KSerializer<STORAGE> = serializerOrContextual()
        override suspend fun willConnect(request: WebSocketConnectRequest): STORAGE = willConnect(request)
        override suspend fun didConnect(connection: WebSocketConnection<STORAGE>) = connection.didConnect()
        override suspend fun messageFromClient(connection: WebSocketConnection<STORAGE>, frame: WebSocketFrame) = connection.message(frame)
        override suspend fun messageFromSubscription(
            connection: WebSocketConnection<STORAGE>, topic: String,
            retrieve: TypeRetriever
        ) = connection.subscription(topic, retrieve)
        override suspend fun disconnect(connection: WebSocketConnection<STORAGE>, reason: WebSocketClose) = connection.disconnect(reason)
    }
    return this
}

@LightningServerDsl
fun ServerPath.websocket(
    handler: WebSocketHandler<*>
): ServerPath {
    WebSockets.handlers[this] = handler
    return this
}
