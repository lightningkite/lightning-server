package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.http.HttpEndpoint
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.Locationed
import com.lightningkite.lightningserver.pathing.MutablePathSpecMap
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpecMap
import com.lightningkite.lightningserver.Serialization
import com.lightningkite.lightningserver.http.HttpInterceptors
import com.lightningkite.lightningserver.http.HttpMethod
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketHandlerInterceptors
import kotlinx.serialization.modules.SerializersModule

public abstract class ServerBuilder {
    public abstract val internalSerialization: SerializersModule
    public abstract val externalSerialization: SerializersModule

    public val http: HttpBuilder = HttpBuilder()
    public val websockets: WebSocketsBuilder = WebSocketsBuilder()
}

public class DuplicateRegistrationError(message: String) : Error(message)

/**
 * An [Map] that allows you to add items to it. Once an item is added
 * to a [Registry], it is considered immutable. It cannot be overwritten or removed.
 * */
public interface Registry<L, V> : Map<L, V> {
    /**
     * Adds the [value] to the underlying [Map] with the given [location].
     *
     * Unlike [MutableMap], registering two values to the same location will throw a [DuplicateRegistrationError].
     * The value at each location is considered immutable once it has been set.
     * */
    public fun <PATH : L> register(location: PATH, value: V): Locationed<PATH, V>
}

public class HttpBuilder {
    public val interceptors: HttpInterceptors = HttpInterceptors()

    private val _handlers = MutablePathSpecMap<HashMap<HttpMethod, HttpHandler<*>>>()
    public val handlers: PathSpecMap<Map<HttpMethod, HttpHandler<*>>> get() = _handlers

    public fun <PATH : PathSpec> register(
        endpoint: HttpEndpoint<PATH>,
        handler: HttpHandler<PATH>
    ): Locationed<HttpEndpoint<PATH>, HttpHandler<PATH>> {
        val methodsMap = _handlers.getOrPut(endpoint.path, ::HashMap)

        if (methodsMap.containsKey(endpoint.method)) throw DuplicateRegistrationError("Path ${endpoint.path} has already registered an endpoint for ${endpoint.method}.")

        methodsMap[endpoint.method] = handler

        return Locationed(endpoint, handler)
    }
}

public class WebSocketsBuilder {
    public val interceptors: WebSocketHandlerInterceptors = WebSocketHandlerInterceptors()

    private val _handlers = MutablePathSpecMap<WebSocketHandler<*, *>>()
    public val handlers: PathSpecMap<WebSocketHandler<*, *>> get() = _handlers

    public fun <PATH : PathSpec, STORAGE> register(
        path: PATH,
        handler: WebSocketHandler<PATH, STORAGE>
    ): Locationed<PATH, WebSocketHandler<PATH, STORAGE>> {
        if (_handlers.containsKey(path)) throw DuplicateRegistrationError("Path $path already has a registered WebSocketHandler")

        _handlers[path] = handler

        return Locationed(path, handler)
    }
}