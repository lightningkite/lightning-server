package com.lightningkite.lightningserver


public abstract class Request<PATH: PathSpec>: PathSpecResolvableInServerRunning<PATH> {
    public abstract val path: PathServer<PATH>
    public abstract val queryParameters: List<Pair<String, String>>
    public abstract val headers: HttpHeaders
    public abstract val domain: String
    public abstract val protocol: String
    public abstract val sourceIp: String
    public abstract val cache: KeyedSerializableCache

    context(serverRunning: ServerRunning)
    override val resolvable: PathSpecResolvable<PATH>
        get() = path.resolvable

    public fun queryParameter(key: String): String? = queryParameters.find { it.first == key }?.second
}

context(server: ServerRunning)
public suspend operator fun <T> Request<*>.get(key: KeyedSerializableCache.Key<T>): T {
    return cache.get(server, this, key)
}