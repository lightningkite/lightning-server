package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.definition.ServerPathEndpoints
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ApiWebsocketHandler

public data class ServerApiEndpoints(
    override val http: Map<HttpMethod, ApiHttpHandler<*, *, *, *>>,
    override val websocket: ApiWebsocketHandler<*, *, *, *, *>?,
) : ServerPathEndpoints {
    public constructor(endpoints: ServerPathEndpoints) : this(
        http = buildMap {
            endpoints.http.forEach { (key, endpoint) ->
                if (endpoint is ApiHttpHandler<*, *, *, *>) put(key, endpoint)
            }
        },
        websocket = endpoints.websocket as? ApiWebsocketHandler<*, *, *, *, *>
    )

    public fun isEmpty(): Boolean = http.isEmpty() && websocket == null
    public fun isNotEmpty(): Boolean = !isEmpty()

    public fun filterSafeEndpoints(): ServerApiEndpoints = copy(
        http = http.filter { (method, handler) -> method != HttpMethod.GET || handler.inputType.isUnit() }
    )
}
