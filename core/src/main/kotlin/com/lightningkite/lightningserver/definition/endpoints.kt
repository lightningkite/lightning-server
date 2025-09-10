package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.websockets.WebSocketHandler

public interface ServerPathEndpoints {
    public val http: Map<HttpMethod, HttpHandler<*>>
    public val websocket: WebSocketHandler<*, *>?
}

private data class ServerPathEndpointsData(
    override val http: Map<HttpMethod, HttpHandler<*>>,
    override val websocket: WebSocketHandler<*, *>?
) : ServerPathEndpoints

public fun ServerPathEndpoints(
    http: Map<HttpMethod, HttpHandler<*>>,
    websocket: WebSocketHandler<*, *>?
): ServerPathEndpoints = ServerPathEndpointsData(http, websocket)


public class MutableServerPathEndpoints : ServerPathEndpoints {
    override val http: MutableMap<HttpMethod, HttpHandler<*>> = HashMap()
    override var websocket: WebSocketHandler<*, *>? = null
}
