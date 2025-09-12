package com.lightningkite.lightningserver.data

import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.pathing.ConcretePath
import com.lightningkite.lightningserver.pathing.HasContextualPath
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawPath
import com.lightningkite.lightningserver.runtime.ServerRuntime


public abstract class Request<PATH: PathSpec>: HasContextualPath<PATH>, Caching {
    public abstract val path: RawPath<PATH>
    public abstract val queryParameters: List<Pair<String, String>>
    public abstract val headers: HttpHeaders
    public abstract val domain: String
    public abstract val protocol: String
    public abstract val sourceIp: String

    context(serverRuntime: ServerRuntime)
    override val pathInContext: ConcretePath<PATH>
        get() = path.pathInContext

    public fun queryParameter(key: String): String? = queryParameters.find { it.first == key }?.second

    public val queryParametersAsString: String get() = queryParameters.joinToString("&") { "${it.first}=${it.second}" }  // TODO: Encode
}

context(server: ServerRuntime)
public suspend operator fun <T> Request<*>.get(key: SerializableCache.CalculatingKey<Request<*>, T>): T {
    return cache.get(key, this)
}