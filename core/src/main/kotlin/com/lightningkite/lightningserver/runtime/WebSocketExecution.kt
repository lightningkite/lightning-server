package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// Pre-allocated TelemetryKey instances (backend caches by equality).
private val wsRoute = TelemetryKey.OfString("ws.route")
private val wsFrameType = TelemetryKey.OfString("ws.frame.type")
private val wsFrameSize = TelemetryKey.OfLong("ws.frame.size")
private val wsSubscriptionTopic = TelemetryKey.OfString("ws.subscription.topic")
private val wsDisconnectCode = TelemetryKey.OfLong("ws.disconnect.code")
private val wsDisconnectReason = TelemetryKey.OfString("ws.disconnect.reason")

@OptIn(InternalLightningServerApi::class)
context(engine: Engine)
private fun <PATH : PathSpec, STORAGE> WebSocketConnection<PATH, STORAGE>.phase(
    phase: Execution.WebSocket.Phase,
): Execution.WebSocket = Execution.WebSocket(
    id = Execution.ID.generate(),
    parent = (engine as? ServerRuntime)?.execution,
    socketId = socketId,
    path = request.path,
    phase = phase,
)

/**
 * Wraps a WebSocket willConnect handler invocation with telemetry metrics.
 *
 * Unlike the other phases, there is no connection yet to derive an identity from, so [initiator] is
 * supplied rather than derived.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param initiator The socket's connect initiator, minted by the engine
 * @param request The WebSocket connection request
 * @return The connection storage state
 */
@OptIn(InternalLightningServerApi::class)
context(engine: Engine)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.willConnectWithMetrics(
    location: PATH,
    initiator: Execution.WebSocket,
    request: WebSocketConnectRequest<PATH>,
): STORAGE {
    return engine.execute("willConnect", initiator, TelemetryAttributes {
        put(wsRoute, location.toString())
        put(TelemetryKeys.Net.peerIp, request.sourceIp)
    }) {
        willConnect(request)
    }
}

/**
 * Wraps a WebSocket didConnect handler invocation with telemetry metrics.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param connection The established WebSocket connection
 */
@OptIn(InternalLightningServerApi::class)
context(engine: Engine)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.didConnectWithMetrics(
    location: PATH,
    connection: WebSocketConnection<PATH, STORAGE>,
) {
    engine.execute(
        "didConnect",
        connection.phase(Execution.WebSocket.Phase.Connected),
        TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, connection.request.sourceIp)
        }
    ) {
        didConnect(connection)
    }
}

/**
 * Wraps a WebSocket messageFromClient handler invocation with telemetry metrics.
 *
 * Records the frame type (text/binary) and size in telemetry.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param connection The WebSocket connection
 * @param frame The frame received from the client
 */
@OptIn(InternalLightningServerApi::class)
context(engine: Engine)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromClientWithMetrics(
    location: PATH,
    connection: WebSocketConnection<PATH, STORAGE>,
    frame: WebSocketFrame,
) {
    engine.execute(
        "messageFromClient",
        connection.phase(Execution.WebSocket.Phase.ClientMessage),
        TelemetryAttributes {
            put(wsRoute, location.toString())
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
    ) {
        messageFromClient(connection, frame)
    }
}

/**
 * Wraps a WebSocket messageFromSubscription handler invocation with telemetry metrics.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param connection The WebSocket connection
 * @param topic The subscription message received
 */
@OptIn(InternalLightningServerApi::class)
context(engine: Engine)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromSubscriptionWithMetrics(
    location: PATH,
    connection: WebSocketConnection<PATH, STORAGE>,
    topic: WebSocketSubscriptionMessage<*, *>,
) {
    engine.execute(
        "messageFromSubscription",
        connection.phase(Execution.WebSocket.Phase.SubscriptionMessage),
        TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, connection.request.sourceIp)
            put(wsSubscriptionTopic, topic.topic.location.toString())
        }
    ) {
        messageFromSubscription(connection, topic)
    }
}

/**
 * Wraps a WebSocket disconnect handler invocation with telemetry metrics, ensuring that [WebSocketConnection.close]
 * is called afterward.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param connection The WebSocket connection being closed
 * @param reason The close reason and code
 */
@OptIn(InternalLightningServerApi::class)
context(engine: Engine)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.disconnectAndClose(
    location: PATH,
    connection: WebSocketConnection<PATH, STORAGE>,
    reason: WebSocketClose,
) {
    engine.execute(
        "disconnect",
        connection.phase(Execution.WebSocket.Phase.Disconnect),
        TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, connection.request.sourceIp)
            put(wsDisconnectCode, reason.code.toLong())
            put(wsDisconnectReason, reason.name)
        }
    ) {
        try {
            disconnect(connection, reason)
        } finally {
            connection.close(reason)
        }
    }
}
