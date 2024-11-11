package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.serialization.serializerOrContextual
import kotlinx.serialization.KSerializer

@LightningServerDsl
inline fun <reified STORAGE> ServerPath.websocket(
    noinline willConnect: suspend (WebSocketConnectRequest) -> STORAGE,
    noinline didConnect: suspend MidWebsocket<STORAGE>.(WebSocketConnectRequest) -> Unit = { },
    noinline message: suspend MidWebsocket<STORAGE>.(WebSocketFrame) -> Unit = { },
    noinline subscription: suspend MidWebsocket<STORAGE>.(topic: String, retriever: TypeRetriever) -> Unit = { _, _ -> },
    noinline disconnect: suspend MidWebsocket<STORAGE>.(WebSocketClose) -> Unit = {}
): ServerPath {
    WebSockets.handlers[this] = object : WebSocketHandler<STORAGE> {
        override val storageSerializer: KSerializer<STORAGE> = serializerOrContextual()
        override suspend fun willConnect(request: WebSocketConnectRequest): STORAGE = willConnect(request)
        override suspend fun didConnect(connection: MidWebsocket<STORAGE>, request: WebSocketConnectRequest) = connection.didConnect(request)
        override suspend fun messageFromClient(connection: MidWebsocket<STORAGE>, frame: WebSocketFrame) = connection.message(frame)
        override suspend fun messageFromSubscription(connection: MidWebsocket<STORAGE>,
            topic: String,
            retrieve: TypeRetriever
        ) = connection.subscription(topic, retrieve)
        override suspend fun disconnect(connection: MidWebsocket<STORAGE>, reason: WebSocketClose) = connection.disconnect(reason)
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
