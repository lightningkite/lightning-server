package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.HttpEndpoint
import com.lightningkite.lightningserver.HttpHandler
import com.lightningkite.lightningserver.Locationed
import com.lightningkite.lightningserver.MutablePathSpecMap
import com.lightningkite.lightningserver.PathSpec
import com.lightningkite.lightningserver.PathSpec0
import com.lightningkite.lightningserver.PathSpecMap
import com.lightningkite.lightningserver.Serialization
import com.lightningkite.lightningserver.http.HttpInterceptors
import com.lightningkite.lightningserver.http.HttpMethod

public abstract class ServerDefinition2 {
    public abstract val internalSerialization: Serialization
    public abstract val externalSerialization: Serialization

    public val http: HTTP = HTTP()


}

public interface Registry<out T> : Map<PathSpec0, T> {

}

public class HTTP {
    public val interceptors: HttpInterceptors = HttpInterceptors()

    private val _handlers = MutablePathSpecMap<HashMap<HttpMethod, HttpHandler<*>>>()
    public val handlers: PathSpecMap<Map<HttpMethod, HttpHandler<*>>> get() = _handlers

    public fun <PATH : PathSpec> register(
        endpoint: HttpEndpoint<PATH>,
        handler: HttpHandler<PATH>
    ): Locationed<HttpEndpoint<PATH>, HttpHandler<PATH>> {
        _handlers.getOrPut(endpoint.path, ::HashMap)[endpoint.method] = handler
        return Locationed(endpoint, handler)
    }
}