package com.lightningkite.lightningserver

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable


public interface WebSocketHandler<PATH: PathSpec, STORAGE> {
    public val storageSerializer: KSerializer<STORAGE>
    public suspend fun willConnect(serverRunning: ServerRunning, request: WebSocketConnectRequest<PATH>): STORAGE
    public suspend fun didConnect(connection: WebSocketConnection<PATH, STORAGE>)
    public suspend fun messageFromClient(connection: WebSocketConnection<PATH, STORAGE>, frame: WebSocketFrame)
    public suspend fun messageFromSubscription(connection: WebSocketConnection<PATH, STORAGE>, topic: WebSocketSubscriptionMessage<*, *>)
    public suspend fun disconnect(connection: WebSocketConnection<PATH, STORAGE>, reason: WebSocketClose)
}

public class WebSocketTopic<PATH: PathSpec, T> internal constructor(
    public val pathSpec: PATH,
    public val type: KSerializer<T>
) {
}
public data class WebSocketSubscriptionRequest<PATH: PathSpec, T>(
    val topic: WebSocketTopic<PATH, T>,
    override val rawPathArguments: List<Any?>,
): PathSpecResolvable<PATH> {
    override val pathSpec: PATH get() = topic.pathSpec
}
public data class WebSocketSubscriptionMessage<PATH: PathSpec, T>(
    val topic: WebSocketTopic<PATH, T>,
    override val rawPathArguments: List<Any?>,
    val value: T
): PathSpecResolvable<PATH> {
    override val pathSpec: PATH get() = topic.pathSpec
}

public sealed interface WebSocketFrame {
    public val content: Any

    public companion object {
        public operator fun invoke(content: String): Text = Text(content)
        public operator fun invoke(content: ByteArray): Binary = Binary(content)
    }

    @JvmInline
    public value class Text(override val content: String) : WebSocketFrame {
        override fun toString(): String = content
    }

    @JvmInline
    public value class Binary(override val content: ByteArray) : WebSocketFrame {
        @OptIn(ExperimentalStdlibApi::class)
        override fun toString(): String = "<bytes ${content.toHexString()}>"
    }
}

public val WebSocketFrame.text: String
    get() = when (this) {
        is WebSocketFrame.Binary -> content.toHexString()
        is WebSocketFrame.Text -> content
    }

@Serializable
public data class WebSocketConnectRequest<PATH: PathSpec>(
    override val path: PathServer<PATH>,
    override val queryParameters: List<Pair<String, String>> = listOf(),
    override val headers: HttpHeaders = HttpHeaders.EMPTY,
    override val domain: String = "",
    override val protocol: String = "",
    override val sourceIp: String = "",
    override val cache: KeyedSerializableCache = KeyedSerializableCache(),
) : Request<PATH>() {
}

public interface WebSocketConnection<PATH: PathSpec, STORAGE>: ServerRunning {
    public val request: WebSocketConnectRequest<PATH>
    public val currentState: STORAGE
    public suspend fun repullState(): STORAGE
    public suspend fun queueStateUpdate(modification: (STORAGE) -> STORAGE)
    public suspend fun updateStateImmediately(modification: (STORAGE) -> STORAGE): STORAGE
    public suspend fun subscribe(topic: WebSocketSubscriptionRequest<*, *>)
    public suspend fun unsubscribe(topic: WebSocketSubscriptionRequest<*, *>)
    public suspend fun send(frame: WebSocketFrame)
    public suspend fun close(reason: WebSocketClose)
}

public suspend fun WebSocketConnection<*, *>.send(content: String): Unit = send(WebSocketFrame(content))
public suspend fun WebSocketConnection<*, *>.send(content: ByteArray): Unit = send(WebSocketFrame(content))

