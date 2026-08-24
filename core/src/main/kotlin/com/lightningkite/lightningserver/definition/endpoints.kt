package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.websockets.WebSocketHandler

/**
 * Represents all endpoint handlers registered at a specific path.
 *
 * A path can have multiple HTTP handlers (one per HTTP method) and optionally one WebSocket handler.
 * This interface provides a unified view of all handlers available at a given path location.
 *
 * @property http A map of HTTP methods to their corresponding handlers for this path
 * @property webSocket An optional WebSocket handler for this path, or null if no WebSocket is registered
 */
public interface ServerPathEndpoints {
    public val http: Map<HttpMethod, HttpHandler<*>>
    public val webSocket: WebSocketHandler<*, *>?
}

private data class ServerPathEndpointsData(
    override val http: Map<HttpMethod, HttpHandler<*>>,
    override val webSocket: WebSocketHandler<*, *>?,
) : ServerPathEndpoints

/**
 * Creates a [ServerPathEndpoints] instance with the specified HTTP and WebSocket handlers.
 *
 * @param http A map of HTTP methods to handlers for this path
 * @param webSocket An optional WebSocket handler for this path
 * @return A new [ServerPathEndpoints] instance
 */
public fun ServerPathEndpoints(
    http: Map<HttpMethod, HttpHandler<*>>,
    webSocket: WebSocketHandler<*, *>?,
): ServerPathEndpoints = ServerPathEndpointsData(http, webSocket)

/**
 * A mutable implementation of [ServerPathEndpoints] that allows adding handlers after construction.
 *
 * This is used during server building to accumulate handlers before creating the final immutable
 * [ServerDefinition]. HTTP handlers can be added to the mutable [http] map, and the [webSocket]
 * handler can be set directly.
 */
public class MutableServerPathEndpoints : ServerPathEndpoints {
    override val http: MutableMap<HttpMethod, HttpHandler<*>> = HashMap()
    override var webSocket: WebSocketHandler<*, *>? = null
}
