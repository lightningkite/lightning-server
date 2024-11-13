package com.lightningkite.lightningserver.websocket

import com.github.jershell.kbson.ByteArraySerializer
import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.engine.engine
import com.lightningkite.lightningserver.exceptions.report
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.serialization.TypeRetriever
import com.lightningkite.lightningserver.typed.AuthAndPathParts
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

suspend fun <STORAGE> WebSocketHandler<STORAGE>.messageFromSubscriptionTracked(path: ServerPath, mid: MidWebsocket<STORAGE>, topic: String, retriever: TypeRetriever) {
    Metrics.handlerPerformance<Unit>(
        WebSockets.HandlerSection(
            path,
            WebSockets.WsHandlerType.WSSUB
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

suspend fun <STORAGE> WebSocketHandler<STORAGE>.messageFromClientTracked(path: ServerPath, mid: MidWebsocket<STORAGE>, message: WebSocketFrame) {
    Metrics.handlerPerformance<Unit>(
        WebSockets.HandlerSection(
            path,
            WebSockets.WsHandlerType.MESSAGE
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

suspend fun <STORAGE> WebSocketHandler<STORAGE>.didConnectTracked(path: ServerPath, mid: MidWebsocket<STORAGE>, request: WebSocketConnectRequest) {
    Metrics.handlerPerformance<Unit>(
        WebSockets.HandlerSection(
            path,
            WebSockets.WsHandlerType.CONNECTED
        )
    ) {
        try {
            didConnect(mid, request)
        } catch(e: Exception) {
            e.report()
            throw e
        }
    }
}

suspend fun <STORAGE> WebSocketHandler<STORAGE>.willConnectTracked(path: ServerPath, request: WebSocketConnectRequest): STORAGE {
    return Metrics.handlerPerformance<STORAGE>(
        WebSockets.HandlerSection(
            path,
            WebSockets.WsHandlerType.CONNECTING
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

suspend fun <STORAGE> WebSocketHandler<STORAGE>.disconnectTracked(path: ServerPath, mid: MidWebsocket<STORAGE>, reason: WebSocketClose) {
    return Metrics.handlerPerformance<Unit>(
        WebSockets.HandlerSection(
            path,
            WebSockets.WsHandlerType.DISCONNECT
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
