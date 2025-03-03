package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.core.ServerEntryPoint
import com.lightningkite.lightningserver.core.ServerPath

data class HttpEndpoint(val path: ServerPath, val method: HttpMethod): ServerEntryPoint {
    constructor(string: String, method: HttpMethod) : this(
        path = ServerPath(string),
        method = method
    )

    override fun toString(): String = "$method $path"
}