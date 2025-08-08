package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.pathing.ConcretePath
import com.lightningkite.lightningserver.pathing.HasContextualPath
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.ServerPath
import com.lightningkite.lightningserver.runtime.ServerRuntime


public abstract class Request<PATH: PathSpec>: HasContextualPath<PATH> {
    public abstract val path: ServerPath<PATH>
    public abstract val queryParameters: List<Pair<String, String>>
    public abstract val headers: HttpHeaders
    public abstract val domain: String
    public abstract val protocol: String
    public abstract val sourceIp: String
    public abstract val cache: KeyedSerializableCache

    context(serverRuntime: ServerRuntime)
    override val pathInContext: ConcretePath<PATH>
        get() = path.pathInContext

    public fun queryParameter(key: String): String? = queryParameters.find { it.first == key }?.second
}

context(server: ServerRuntime)
public suspend operator fun <T> Request<*>.get(key: KeyedSerializableCache.Key<T>): T {
    return cache.get(server, this, key)
}