package com.lightningkite.lightningserver.websocket

import com.lightningkite.lightningserver.core.ServerPath
import com.lightningkite.lightningserver.metrics.Metrics
import com.lightningkite.lightningserver.serialization.Serialization
import com.lightningkite.lightningserver.typed.AuthAndPathParts
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(AnonTypeSerializer::class)
class AnonType {
    var serialized: String? = null
    var serializer: KSerializer<*>? = null
    var direct: Any? = null
    var hasDirect: Boolean = false

    constructor(direct: Any?, serializer: KSerializer<*>) {
        this.direct = direct
        hasDirect = true
        this.serializer = serializer
    }
    constructor(serialized: String) {
        this.serialized = serialized
    }

    fun serialized(): String {
        return serialized ?: run {
            val newSer = Serialization.Internal.json.encodeToString(serializer as KSerializer<Any?>, direct)
            serialized = newSer
            newSer
        }
    }
    fun <T> value(serializer: KSerializer<T>): T {
        if(hasDirect) return direct as T
        hasDirect = true
        val d = Serialization.Internal.json.decodeFromString(serializer, serialized!!)
        direct = d
        return d
    }
}

@JvmInline
value class TypeRetriever(val retriever: (KSerializer<*>) -> Any?) {
    @Suppress("UNCHECKED_CAST")
    operator fun <T> invoke(serializer: KSerializer<T>): T = retriever(serializer) as T
    companion object {
        fun of(retriever: (KSerializer<Nothing>) -> Nothing): TypeRetriever {
            @Suppress("UNCHECKED_CAST")
            return TypeRetriever(retriever as (KSerializer<*>) -> Any?)
        }
        fun literal(value: Any?) = TypeRetriever { value }
    }
}

object AnonTypeSerializer: KSerializer<AnonType> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("AnonType", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): AnonType = AnonType(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: AnonType) = encoder.encodeString(value.serialized())
}

suspend fun <STORAGE> WebSocketHandler<STORAGE>.messageFromSubscriptionTracked(path: ServerPath, mid: MidWebsocket<STORAGE>, topic: String, retriever: TypeRetriever) {
    Metrics.handlerPerformance<Unit>(
        WebSockets.HandlerSection(
            path,
            WebSockets.WsHandlerType.WSSUB
        )
    ) {
        messageFromSubscription(mid, topic, retriever)
    }
}

suspend fun <STORAGE> WebSocketHandler<STORAGE>.messageFromClientTracked(path: ServerPath, mid: MidWebsocket<STORAGE>, message: WebSocketFrame) {
    Metrics.handlerPerformance<Unit>(
        WebSockets.HandlerSection(
            path,
            WebSockets.WsHandlerType.MESSAGE
        )
    ) {
        messageFromClient(mid, message)
    }
}

suspend fun <STORAGE> WebSocketHandler<STORAGE>.didConnectTracked(path: ServerPath, mid: MidWebsocket<STORAGE>, request: WebSocketConnectRequest) {
    Metrics.handlerPerformance<Unit>(
        WebSockets.HandlerSection(
            path,
            WebSockets.WsHandlerType.CONNECTED
        )
    ) {
        didConnect(mid, request)
    }
}

suspend fun <STORAGE> WebSocketHandler<STORAGE>.willConnectTracked(path: ServerPath, request: WebSocketConnectRequest): STORAGE {
    return Metrics.handlerPerformance<STORAGE>(
        WebSockets.HandlerSection(
            path,
            WebSockets.WsHandlerType.CONNECTING
        )
    ) {
        willConnect(request)
    }
}

suspend fun <STORAGE> WebSocketHandler<STORAGE>.disconnectTracked(path: ServerPath, mid: MidWebsocket<STORAGE>, reason: WebSocketClose) {
    return Metrics.handlerPerformance<Unit>(
        WebSockets.HandlerSection(
            path,
            WebSockets.WsHandlerType.DISCONNECT
        )
    ) {
        disconnect(mid, reason)
    }
}
