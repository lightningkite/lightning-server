package com.lightningkite.lightningserver.typed.sdk

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.definition.ServerPathEndpoints
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ApiWebsocketHandler

public interface ServerApiEndpoints : ServerPathEndpoints {
    override val http: Map<HttpMethod, ApiHttpHandler<*, *, *, *>>
    override val websocket: ApiWebsocketHandler<*, *, *, *, *>?
}
