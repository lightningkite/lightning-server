package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlinx.serialization.KSerializer

/**
 * A handler that passes every phase through to [wrapped], so a subclass overrides only the phases it
 * actually has something to say about.
 *
 * Wrapping a socket almost always means acting on one or two of the five phases — an interceptor that
 * logs the open and the close has nothing to add to the three in between — and hand-writing the rest
 * as pass-throughs both buries the interesting override and makes every wrapper a place a future
 * phase can be forgotten.
 */
public abstract class DelegatingWebSocketHandler<PATH : PathSpec, STORAGE>(
    protected val wrapped: WebSocketHandler<PATH, STORAGE>,
) : WebSocketHandler<PATH, STORAGE> {
    override val storageSerializer: KSerializer<STORAGE> get() = wrapped.storageSerializer

    context(serverRuntime: ServerRuntime)
    override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): STORAGE =
        wrapped.willConnect(request)

    context(serverRuntime: ServerRuntime)
    override suspend fun didConnect(connection: WebSocketConnection<PATH, STORAGE>): Unit =
        wrapped.didConnect(connection)

    context(serverRuntime: ServerRuntime)
    override suspend fun messageFromClient(
        connection: WebSocketConnection<PATH, STORAGE>,
        frame: WebSocketFrame,
    ): Unit = wrapped.messageFromClient(connection, frame)

    context(serverRuntime: ServerRuntime)
    override suspend fun messageFromSubscription(
        connection: WebSocketConnection<PATH, STORAGE>,
        topic: WebSocketSubscriptionMessage<*, *>,
    ): Unit = wrapped.messageFromSubscription(connection, topic)

    context(serverRuntime: ServerRuntime)
    override suspend fun disconnect(
        connection: WebSocketConnection<PATH, STORAGE>,
        reason: WebSocketClose,
    ): Unit = wrapped.disconnect(connection, reason)
}
