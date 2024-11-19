package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.serialization.TypeRetriever

suspend fun <STORAGE> WebSocketHandler<STORAGE>.messageFromSubscriptionTracked(path: ServerPath, mid: WebSocketConnection<STORAGE>, topic: String, retriever: TypeRetriever) {
    Metrics.handlerPerformance<Unit>(
        WebSockets.HandlerContext(
            path,
            WebSockets.WsHandlerType.WSSUB,
            mid.request
        )
    ) {
        try {
            messageFromSubscription(mid, topic, retriever)
        } catch(e: Exception) {
            e.report()
            throw e
        }
    }
}

suspend fun <STORAGE> WebSocketHandler<STORAGE>.messageFromClientTracked(path: ServerPath, mid: WebSocketConnection<STORAGE>, message: WebSocketFrame) {
    Metrics.handlerPerformance<Unit>(
        WebSockets.HandlerContext(
            path,
            WebSockets.WsHandlerType.MESSAGE,
            mid.request
        )
    ) {
        try {
            messageFromClient(mid, message)
        } catch(e: Exception) {
            e.report()
            throw e
        }
    }
}

suspend fun <STORAGE> WebSocketHandler<STORAGE>.didConnectTracked(path: ServerPath, mid: WebSocketConnection<STORAGE>) {
    Metrics.handlerPerformance<Unit>(
        WebSockets.HandlerContext(
            path,
            WebSockets.WsHandlerType.CONNECTED,
            mid.request,
        )
    ) {
        try {
            didConnect(mid)
        } catch(e: Exception) {
            e.report()
            throw e
        }
    }
}

suspend fun <STORAGE> WebSocketHandler<STORAGE>.willConnectTracked(path: ServerPath, request: WebSocketConnectRequest): STORAGE {
    return Metrics.handlerPerformance<STORAGE>(
        WebSockets.HandlerContext(
            path,
            WebSockets.WsHandlerType.CONNECTING,
            request,
        )
    ) {
        try {
            willConnect(request)
        } catch(e: Exception) {
            e.report()
            throw e
        }
    }
}

suspend fun <STORAGE> WebSocketHandler<STORAGE>.disconnectTracked(
    path: ServerPath,
    mid: WebSocketConnection<STORAGE>,
    reason: WebSocketClose
) {
    return Metrics.handlerPerformance<Unit>(
        WebSockets.HandlerContext(
            path,
            WebSockets.WsHandlerType.DISCONNECT,
            mid.request
        )
    ) {
        try {
            disconnect(mid, reason)
        } catch(e: Exception) {
            e.report()
            throw e
        }
    }
}
