package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.definition.ServerSetting
import com.lightningkite.services.data.DataSize
import com.lightningkite.services.data.DataSize.Companion.mebibytes
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Transport-level limits for webSocket connections.
 *
 * The defaults here are deliberately stricter than the underlying engines'. Ktor disables server-side
 * pings and leaves the maximum frame size unbounded, which means a server has no way to notice a peer
 * that has stopped reading: its outbound buffers simply fill and stay filled. Enough such connections
 * will exhaust the heap.
 *
 * @property ping how often the server pings each connection, or null to disable pings entirely
 *   (the engine default, and the reason a stalled consumer can go unnoticed). With pings enabled, a
 *   connection is closed if a ping cannot be *sent* or is not answered within [pongTimeout], so
 *   this detects both an unresponsive peer and one that has stopped draining.
 * @property pongTimeout how long to wait for a ping to be sent and answered before closing.
 *   Worst-case detection time for a stalled consumer is roughly [ping] + this.
 * @property maxFrameSize largest frame that may be sent or received; null leaves it unbounded. This
 *   bounds an inbound allocation driven entirely by the peer, so leaving it unbounded lets any client
 *   ask the server to allocate arbitrarily.
 */
@Serializable
public data class WebSocketSettings(
    val ping: Duration? = 30.seconds,
    val pongTimeout: Duration = 15.seconds,
    val maxFrameSize: DataSize? = 16.mebibytes,
)

public val webSocketSettings: ServerSetting.Direct<WebSocketSettings> = ServerSetting(
    "webSocketSettings",
    WebSocketSettings(),
    WebSocketSettings.serializer(),
    optional = true
)
