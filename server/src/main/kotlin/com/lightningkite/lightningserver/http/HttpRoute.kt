package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.core.ServerPath

data class HttpRoute(val path: ServerPath, val method: HttpMethod) {
    constructor(string: String, method: HttpMethod) : this(
        path = ServerPath(string),
        method = method
    )

    override fun toString(): String = "$method $path"
}