package com.lightningkite.lightningserver.data

import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.pathing.ResolvedPath
import com.lightningkite.lightningserver.pathing.HasContextualPath
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime


public abstract class Request<out PATH: PathSpec>: HasContextualPath<PATH>, Caching {
    public abstract val path: HasContextualPath<PATH>
    public abstract val queryParameters: QueryParameters
    public abstract val headers: HttpHeaders
    public abstract val domain: String
    public abstract val protocol: String
    public abstract val sourceIp: String

    context(serverRuntime: ServerRuntime)
    override val pathInContext: ResolvedPath<PATH>
        get() = path.pathInContext
}

context(server: ServerRuntime)
public suspend operator fun <T> Request<*>.get(key: SerializableCache.CalculatingKey<Request<*>, T>): T {
    return cache.get(key, this)
}