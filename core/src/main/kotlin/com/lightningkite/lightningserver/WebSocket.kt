package com.lightningkite.lightningserver

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable


public class WebSocketTopic<PATH: PathSpec, T> internal constructor(
    public val pathSpec: PATH,
    public val type: KSerializer<T>
) {
    override fun equals(other: Any?): Boolean = other is WebSocketTopic<*, *> && other.pathSpec == pathSpec
    override fun hashCode(): Int = pathSpec.hashCode() + 1
    override fun toString(): String = pathSpec.toString()
}
public fun <T> WebSocketTopic<PathSpec0, T>.request(): WebSocketSubscriptionRequest<PathSpec0, T> = WebSocketSubscriptionRequest(
    topic = this,
    rawPathArguments = listOf()
)
public fun <T, A> WebSocketTopic<PathSpec1<A>, T>.request(path1: A): WebSocketSubscriptionRequest<PathSpec1<A>, T> = WebSocketSubscriptionRequest(
    topic = this,
    rawPathArguments = listOf(path1)
)
public fun <T, A, B> WebSocketTopic<PathSpec2<A, B>, T>.request(path1: A, path2: B): WebSocketSubscriptionRequest<PathSpec2<A, B>, T> = WebSocketSubscriptionRequest(
    topic = this,
    rawPathArguments = listOf(path1, path2)
)
public fun <T, A, B, C> WebSocketTopic<PathSpec3<A, B, C>, T>.request(path1: A, path2: B, path3: C): WebSocketSubscriptionRequest<PathSpec3<A, B, C>, T> = WebSocketSubscriptionRequest(
    topic = this,
    rawPathArguments = listOf(path1, path2, path3)
)

public data class WebSocketSubscriptionRequest<PATH: PathSpec, T>(
    val topic: WebSocketTopic<PATH, T>,
    override val rawPathArguments: List<Any?>,
): ConcretePath<PATH> {
    override val pathSpec: PATH get() = topic.pathSpec
}
public data class WebSocketSubscriptionMessage<PATH: PathSpec, T>(
    val topic: WebSocketTopic<PATH, T>,
    override val rawPathArguments: List<Any?>,
    val value: T
): ConcretePath<PATH> {
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
    override val path: ServerPath<PATH>,
    override val queryParameters: List<Pair<String, String>> = listOf(),
    override val headers: HttpHeaders = HttpHeaders.EMPTY,
    override val domain: String = "",
    override val protocol: String = "",
    override val sourceIp: String = "",
    override val cache: KeyedSerializableCache = KeyedSerializableCache(),
) : Request<PATH>() {
}

public interface WebSocketConnection<PATH: PathSpec, STORAGE>: ServerRuntime {
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

context(connection: WebSocketConnection<PATH, STORAGE>) public suspend fun <PATH: PathSpec, STORAGE, T> subscribe(topic: WebSocketTopic<PathSpec0, T>): Unit = connection.subscribe(topic.request())
context(connection: WebSocketConnection<PATH, STORAGE>) public suspend fun <PATH: PathSpec, STORAGE, T, A> subscribe(topic: WebSocketTopic<PathSpec1<A>, T>, path1: A): Unit = connection.subscribe(topic.request(path1))
context(connection: WebSocketConnection<PATH, STORAGE>) public suspend fun <PATH: PathSpec, STORAGE, T, A, B> subscribe(topic: WebSocketTopic<PathSpec2<A, B>, T>, path1: A, path2: B): Unit = connection.subscribe(topic.request(path1, path2))
context(connection: WebSocketConnection<PATH, STORAGE>) public suspend fun <PATH: PathSpec, STORAGE, T, A, B, C> subscribe(topic: WebSocketTopic<PathSpec3<A, B, C>, T>, path1: A, path2: B, path3: C): Unit = connection.subscribe(topic.request(path1, path2, path3))

public suspend fun WebSocketConnection<*, *>.send(content: String): Unit = send(WebSocketFrame(content))
public suspend fun WebSocketConnection<*, *>.send(content: ByteArray): Unit = send(WebSocketFrame(content))

