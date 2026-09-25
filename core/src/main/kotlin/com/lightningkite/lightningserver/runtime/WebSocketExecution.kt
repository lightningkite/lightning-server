@file:OptIn(InternalLightningServerApi::class)

package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.route
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import kotlinx.coroutines.channels.ReceiveChannel

// Pre-allocated TelemetryKey instances (backend caches by equality).
private val wsRoute = TelemetryKey.OfString("ws.route")
private val wsFrameType = TelemetryKey.OfString("ws.frame.type")
private val wsFrameSize = TelemetryKey.OfLong("ws.frame.size")
private val wsSubscriptionTopic = TelemetryKey.OfString("ws.subscription.topic")
private val wsDisconnectCode = TelemetryKey.OfLong("ws.disconnect.code")
private val wsDisconnectReason = TelemetryKey.OfString("ws.disconnect.reason")

// Each phase runs either as a root, started by whatever drives the socket (an engine, the test runner, a
// pub/sub collector), or as a child of the current execution (a handler driving a nested socket, such as a
// multiplexed channel). They are separate functions, `xAsRoot` and `xWithMetrics`, rather than inferred from
// whether a ServerRuntime happens to be in scope, so a phase can never pick up an unrelated parent. Only
// whatever drives a socket needs the roots, so they are internal, like Engine.handleRoot's role for HTTP.

/**
 * Runs [DirectExecutableWebSocketHandler.handleDirect] as the socket's Connect execution, as a root.
 */
@InternalLightningServerApi
context(engine: Engine)
public suspend fun <PATH : PathSpec, STORAGE> DirectExecutableWebSocketHandler<PATH, STORAGE>.handleDirectAsRoot(
    request: WebSocketConnectRequest<PATH>,
    incoming: ReceiveChannel<WebSocketFrame>,
    send: suspend (WebSocketFrame) -> Unit,
    close: suspend (WebSocketClose) -> Unit,
): Unit = engine.processEngine.execute(
    "handleDirect",
    request.connectPhase(null),
    connectAttributes(request)
) {
    handleDirect(request, incoming, send, close)
}

/**
 * Runs [DirectExecutableWebSocketHandler.handleDirect] as the socket's Connect execution, caused by the
 * current one.
 */
context(runtime: ServerRuntime)
public suspend fun <PATH : PathSpec, STORAGE> DirectExecutableWebSocketHandler<PATH, STORAGE>.handleDirectWithMetrics(
    request: WebSocketConnectRequest<PATH>,
    incoming: ReceiveChannel<WebSocketFrame>,
    send: suspend (WebSocketFrame) -> Unit,
    close: suspend (WebSocketClose) -> Unit,
): Unit = runtime.execute(
    "handleDirect",
    request.connectPhase(runtime.execution),
    connectAttributes(request)
) {
    handleDirect(request, incoming, send, close)
}

/** Runs [WebSocketHandler.willConnect] as the socket's Connect execution, as a root. */
@InternalLightningServerApi
context(engine: Engine)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.willConnectAsRoot(
    request: WebSocketConnectRequest<PATH>,
): STORAGE = engine.processEngine.execute(
    "willConnect",
    request.connectPhase(null),
    connectAttributes(request)
) {
    willConnect(request)
}

/** Runs [WebSocketHandler.willConnect] as the socket's Connect execution, caused by the current one. */
context(runtime: ServerRuntime)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.willConnectWithMetrics(
    request: WebSocketConnectRequest<PATH>,
): STORAGE = runtime.execute(
    "willConnect",
    request.connectPhase(runtime.execution),
    connectAttributes(request)
) {
    willConnect(request)
}

/** Runs [WebSocketHandler.didConnect] as a new root execution. */
@InternalLightningServerApi
context(engine: Engine)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.didConnectAsRoot(
    connection: WebSocketConnection<PATH, STORAGE>,
): Unit = engine.processEngine.execute(
    "didConnect",
    connection.phase(Execution.WebSocket.Phase.Connected, null),
    connectionAttributes(connection)
) {
    didConnect(connection)
}

/** Runs [WebSocketHandler.didConnect] as a new execution caused by the current one. */
context(runtime: ServerRuntime)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.didConnectWithMetrics(
    connection: WebSocketConnection<PATH, STORAGE>,
): Unit = runtime.execute(
    "didConnect",
    connection.phase(Execution.WebSocket.Phase.Connected, runtime.execution),
    connectionAttributes(connection)
) {
    didConnect(connection)
}

/** Runs [WebSocketHandler.messageFromClient] as a new root execution. */
@InternalLightningServerApi
context(engine: Engine)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromClientAsRoot(
    connection: WebSocketConnection<PATH, STORAGE>,
    frame: WebSocketFrame,
): Unit = engine.processEngine.execute(
    "messageFromClient",
    connection.phase(Execution.WebSocket.Phase.ClientMessage, null),
    clientMessageAttributes(connection, frame)
) {
    messageFromClient(connection, frame)
}

/** Runs [WebSocketHandler.messageFromClient] as a new execution caused by the current one. */
context(runtime: ServerRuntime)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromClientWithMetrics(
    connection: WebSocketConnection<PATH, STORAGE>,
    frame: WebSocketFrame,
): Unit = runtime.execute(
    "messageFromClient",
    connection.phase(Execution.WebSocket.Phase.ClientMessage, runtime.execution),
    clientMessageAttributes(connection, frame)
) {
    messageFromClient(connection, frame)
}

/** Runs [WebSocketHandler.messageFromSubscription] as a new root execution. */
@InternalLightningServerApi
context(engine: Engine)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromSubscriptionAsRoot(
    connection: WebSocketConnection<PATH, STORAGE>,
    topic: WebSocketSubscriptionMessage<*, *>,
): Unit = engine.processEngine.execute(
    "messageFromSubscription",
    connection.phase(Execution.WebSocket.Phase.SubscriptionMessage, null),
    subscriptionMessageAttributes(connection, topic)
) {
    messageFromSubscription(connection, topic)
}

/** Runs [WebSocketHandler.messageFromSubscription] as a new execution caused by the current one. */
context(runtime: ServerRuntime)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromSubscriptionWithMetrics(
    connection: WebSocketConnection<PATH, STORAGE>,
    topic: WebSocketSubscriptionMessage<*, *>,
): Unit = runtime.execute(
    "messageFromSubscription",
    connection.phase(Execution.WebSocket.Phase.SubscriptionMessage, runtime.execution),
    subscriptionMessageAttributes(connection, topic)
) {
    messageFromSubscription(connection, topic)
}

/**
 * Runs [WebSocketHandler.disconnect] as a new root execution, then closes [connection] even if the
 * handler throws.
 */
@InternalLightningServerApi
context(engine: Engine)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.disconnectAndCloseAsRoot(
    connection: WebSocketConnection<PATH, STORAGE>,
    reason: WebSocketClose,
): Unit = engine.processEngine.execute(
    "disconnect",
    connection.phase(Execution.WebSocket.Phase.Disconnect, null),
    disconnectAttributes(connection, reason)
) {
    try {
        disconnect(connection, reason)
    } finally {
        connection.close(reason)
    }
}

/**
 * Runs [WebSocketHandler.disconnect] as a new execution caused by the current one, then closes
 * [connection] even if the handler throws.
 */
context(runtime: ServerRuntime)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.disconnectAndClose(
    connection: WebSocketConnection<PATH, STORAGE>,
    reason: WebSocketClose,
): Unit = runtime.execute(
    "disconnect",
    connection.phase(Execution.WebSocket.Phase.Disconnect, runtime.execution),
    disconnectAttributes(connection, reason)
) {
    try {
        disconnect(connection, reason)
    } finally {
        connection.close(reason)
    }
}


// INTERNAL IMPLEMENTATION STUFF

private fun WebSocketConnectRequest<*>.connectPhase(parent: Execution?): Execution.WebSocket =
    Execution.WebSocket(
        id = socketId,
        parent = parent,
        socketId = socketId,
        path = path,
        phase = Execution.WebSocket.Phase.Connect,
    )

context(engine: Engine)
private fun WebSocketConnection<*, *>.phase(
    phase: Execution.WebSocket.Phase,
    parent: Execution?,
): Execution.WebSocket = Execution.WebSocket(
    id = Execution.ID.generate(),
    parent = parent,
    socketId = socketId,
    path = request.path,
    phase = phase,
)

context(engine: Engine)
private fun connectAttributes(request: WebSocketConnectRequest<*>) = TelemetryAttributes {
    put(wsRoute, request.path.route())
    put(TelemetryKeys.Net.peerIp, request.sourceIp)
}

context(engine: Engine)
private fun connectionAttributes(connection: WebSocketConnection<*, *>) = connectAttributes(connection.request)

context(engine: Engine)
private fun clientMessageAttributes(connection: WebSocketConnection<*, *>, frame: WebSocketFrame) = TelemetryAttributes {
    put(wsRoute, connection.request.path.route())
    put(TelemetryKeys.Net.peerIp, connection.request.sourceIp)
    put(
        wsFrameType, when (frame) {
            is WebSocketFrame.Text -> "text"
            is WebSocketFrame.Binary -> "binary"
        }
    )
    put(
        wsFrameSize, when (frame) {
            is WebSocketFrame.Text -> frame.content.length.toLong()
            is WebSocketFrame.Binary -> frame.content.size.toLong()
        }
    )
}

context(engine: Engine)
private fun subscriptionMessageAttributes(
    connection: WebSocketConnection<*, *>,
    topic: WebSocketSubscriptionMessage<*, *>,
) = TelemetryAttributes {
    put(wsRoute, connection.request.path.route())
    put(TelemetryKeys.Net.peerIp, connection.request.sourceIp)
    put(wsSubscriptionTopic, topic.topic.location.toString())
}

context(engine: Engine)
private fun disconnectAttributes(connection: WebSocketConnection<*, *>, reason: WebSocketClose) = TelemetryAttributes {
    put(wsRoute, connection.request.path.route())
    put(TelemetryKeys.Net.peerIp, connection.request.sourceIp)
    put(wsDisconnectCode, reason.code.code.toLong())
    put(wsDisconnectReason, reason.message ?: reason.code.name)
}