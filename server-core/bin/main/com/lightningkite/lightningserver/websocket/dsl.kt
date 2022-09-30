package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.core.LightningServerDsl
import com.lightningkite.lightningserver.core.ServerPath

@LightningServerDsl
fun ServerPath.websocket(
    connect: suspend (WebSockets.ConnectEvent) -> Unit = { },
    message: suspend (WebSockets.MessageEvent) -> Unit = { },
    disconnect: suspend (WebSockets.DisconnectEvent) -> Unit = {}
): ServerPath {
    WebSockets.handlers[this] = object: WebSockets.Handler {
        override suspend fun connect(event: WebSockets.ConnectEvent) = connect(event)
        override suspend fun message(event: WebSockets.MessageEvent) = message(event)
        override suspend fun disconnect(event: WebSockets.DisconnectEvent) = disconnect(event)
    }
    return this
}
