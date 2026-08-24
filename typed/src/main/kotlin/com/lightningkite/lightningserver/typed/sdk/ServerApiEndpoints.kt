package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.definition.ServerPathEndpoints
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ApiWebSocketHandler

public data class ServerApiEndpoints(
    override val http: Map<HttpMethod, ApiHttpHandler<*, *, *, *>>,
    override val webSocket: ApiWebSocketHandler<*, *, *, *, *>?,
) : ServerPathEndpoints {
    public constructor(endpoints: ServerPathEndpoints) : this(
        http = buildMap {
            endpoints.http.forEach { (key, endpoint) ->
                if (endpoint is ApiHttpHandler<*, *, *, *>) put(key, endpoint)
            }
        },
        webSocket = endpoints.webSocket as? ApiWebSocketHandler<*, *, *, *, *>
    )

    public fun isEmpty(): Boolean = http.isEmpty() && webSocket == null
    public fun isNotEmpty(): Boolean = !isEmpty()

    public fun filterSafeEndpoints(): ServerApiEndpoints = copy(
        http = http.filter { (method, handler) -> method != HttpMethod.GET || handler.inputType.isUnit() }
    )
}
