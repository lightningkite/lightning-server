package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.pathing.ResolvedPath
import com.lightningkite.lightningserver.pathing.HasContextualPath
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawWebsocketPath
import com.lightningkite.lightningserver.runtime.location
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable


public class WebSocketTopic<PATH: PathSpec, T> internal constructor(
    public val type: KSerializer<T>
) {
}

public data class WebSocketSubscriptionRequest<PATH: PathSpec, T>(
    val topic: WebSocketTopic<PATH, T>,
    val rawPathArguments: List<Any?>,
): HasContextualPath<PATH> {
    context(server: ServerRuntime)
    override val pathInContext: ResolvedPath<PATH> get() = ResolvedPath(topic.location, rawPathArguments)
}

public data class WebSocketSubscriptionMessage<PATH: PathSpec, T>(
    val topic: WebSocketTopic<PATH, T>,
    val rawPathArguments: List<Any?>,
    val value: T
): HasContextualPath<PATH> {
    context(server: ServerRuntime)
    override val pathInContext: ResolvedPath<PATH> get() = ResolvedPath(topic.location, rawPathArguments)
}


@Serializable
public data class WebSocketConnectRequest<PATH: PathSpec>(
    override val path: RawWebsocketPath<PATH>,
    override val queryParameters: QueryParameters = QueryParameters.EMPTY,
    override val headers: HttpHeaders = HttpHeaders.EMPTY,
    override val domain: String = "",
    override val protocol: String = "",
    override val sourceIp: String = "",
    override val cache: SerializableCache = SerializableCache(),
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


