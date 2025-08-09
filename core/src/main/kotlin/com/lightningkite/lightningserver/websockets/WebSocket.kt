package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.KeyedSerializableCache
import com.lightningkite.lightningserver.Request
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.pathing.ConcretePath
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.ServerPath
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable


public class WebSocketTopic<PATH: PathSpec, T> internal constructor(
    private val path: () -> PATH,
    public val type: KSerializer<T>
) {
    public val pathSpec: PATH get() = path()

    override fun equals(other: Any?): Boolean = other is WebSocketTopic<*, *> && other.pathSpec == pathSpec
    override fun hashCode(): Int = pathSpec.hashCode() + 1
    override fun toString(): String = pathSpec.toString()
}

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


@Serializable
public data class WebSocketConnectRequest<PATH: PathSpec>(
    override val path: ServerPath<PATH>,
    override val queryParameters: List<Pair<String, String>> = listOf(),
    override val headers: HttpHeaders = HttpHeaders.EMPTY,
    override val domain: String = "",
    override val protocol: String = "",
    override val sourceIp: String = "",
    override val cache: KeyedSerializableCache = KeyedSerializableCache(),
) : Request<PATH>()

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


