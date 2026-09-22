package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryKeys

// Pre-allocated TelemetryKey instances (backend caches by equality).
private val wsRoute = TelemetryKey.OfString("ws.route")
private val wsFrameType = TelemetryKey.OfString("ws.frame.type")
private val wsFrameSize = TelemetryKey.OfLong("ws.frame.size")
private val wsSubscriptionTopic = TelemetryKey.OfString("ws.subscription.topic")
private val wsDisconnectCode = TelemetryKey.OfLong("ws.disconnect.code")
private val wsDisconnectReason = TelemetryKey.OfString("ws.disconnect.reason")

/**
 * Wraps a WebSocket willConnect handler invocation with telemetry metrics.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param engine The engine minting this execution
 * @param initiator The socket's connect initiator, minted by the engine and kept with the connection
 *   so that every later phase can derive its own from it with [phase].
 * @param request The WebSocket connection request
 * @return The connection storage state
 */
@OptIn(InternalLightningServerApi::class)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.willConnectWithMetrics(
    location: PATH,
    engine: Engine,
    initiator: Execution.WebSocket,
    request: WebSocketConnectRequest<PATH>,
): STORAGE {
    return engine.execute(initiator) {
        instrument("willConnect", TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, request.sourceIp)
        }) {
            interceptExecution { willConnect(request) }
        }
    }
}

/**
 * Wraps a WebSocket didConnect handler invocation with telemetry metrics.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param engine The engine minting this execution
 * @param initiator This phase's initiator, derived from the socket's connect initiator with
 *   [phase] so that the phase is its own execution while the socket's identity carries over
 * @param connection The established WebSocket connection
 */
@OptIn(InternalLightningServerApi::class)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.didConnectWithMetrics(
    location: PATH,
    engine: Engine,
    initiator: Execution.WebSocket,
    connection: WebSocketConnection<PATH, STORAGE>,
) {
    val runtime = engine.execute(initiator) {
        instrument("didConnect", TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, connection.request.sourceIp)
        }) {
            interceptExecution { didConnect(connection) }
        }
    }
}

/**
 * Wraps a WebSocket messageFromClient handler invocation with telemetry metrics.
 *
 * Records the frame type (text/binary) and size in telemetry.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param engine The engine minting this execution
 * @param initiator This phase's initiator, derived from the socket's connect initiator with
 *   [phase] so that the phase is its own execution while the socket's identity carries over
 * @param connection The WebSocket connection
 * @param frame The frame received from the client
 */
@OptIn(InternalLightningServerApi::class)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromClientWithMetrics(
    location: PATH,
    engine: Engine,
    initiator: Execution.WebSocket,
    connection: WebSocketConnection<PATH, STORAGE>,
    frame: WebSocketFrame,
) {
    engine.execute(initiator) {
        instrument("messageFromClient", TelemetryAttributes {
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
        }) {
            interceptExecution { messageFromClient(connection, frame) }
        }
    }
}

/**
 * Wraps a WebSocket messageFromSubscription handler invocation with telemetry metrics.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param engine The engine minting this execution
 * @param initiator This phase's initiator, derived from the socket's connect initiator with
 *   [phase] so that the phase is its own execution while the socket's identity carries over
 * @param connection The WebSocket connection
 * @param topic The subscription message received
 */
@OptIn(InternalLightningServerApi::class)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.messageFromSubscriptionWithMetrics(
    location: PATH,
    engine: Engine,
    initiator: Execution.WebSocket,
    connection: WebSocketConnection<PATH, STORAGE>,
    topic: WebSocketSubscriptionMessage<*, *>,
) {
    engine.execute(initiator) {
        instrument("messageFromSubscription", TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, connection.request.sourceIp)
            put(wsSubscriptionTopic, topic.topic.location.toString())
        }) {
            interceptExecution { messageFromSubscription(connection, topic) }
        }
    }
}

/**
 * Wraps a WebSocket disconnect handler invocation with telemetry metrics.
 *
 * @param location The path specification for this WebSocket endpoint
 * @param engine The engine minting this execution
 * @param initiator This phase's initiator, derived from the socket's connect initiator with
 *   [phase] so that the phase is its own execution while the socket's identity carries over
 * @param connection The WebSocket connection being closed
 * @param reason The close reason and code
 */
@OptIn(InternalLightningServerApi::class)
public suspend fun <PATH : PathSpec, STORAGE> WebSocketHandler<PATH, STORAGE>.disconnectWithMetrics(
    location: PATH,
    engine: Engine,
    initiator: Execution.WebSocket,
    connection: WebSocketConnection<PATH, STORAGE>,
    reason: WebSocketClose,
) {
    engine.execute(initiator) {
        instrument("disconnect", TelemetryAttributes {
            put(wsRoute, location.toString())
            put(TelemetryKeys.Net.peerIp, connection.request.sourceIp)
            put(wsDisconnectCode, reason.code.toLong())
            put(wsDisconnectReason, reason.name)
        }) {
            interceptExecution { disconnect(connection, reason) }
        }
    }
}

/*
 * TODO: API Recommendations
 *
 * 1. The *WithMetrics functions are public but marked as internal in some cases with @PublishedApi.
 *    Clarify the intended visibility and usage patterns.
 */
