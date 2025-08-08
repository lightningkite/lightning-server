package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.Locationed
import com.lightningkite.lightningserver.definition.DuplicateRegistrationError
import com.lightningkite.lightningserver.pathing.MutablePathSpecMap
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpecMap

public class WebSocketsBuilder {
    public val interceptors: WebSocketHandlerInterceptor.Builder = WebSocketHandlerInterceptor.Builder()
    public val topics: WebSocketTopicsBuilder = WebSocketTopicsBuilder()

    private val _handlers = MutablePathSpecMap<WebSocketHandler<*, *>>()
    public val handlers: PathSpecMap<WebSocketHandler<*, *>> get() = _handlers

    public fun <PATH : PathSpec, STORAGE> register(
        path: PATH,
        handler: WebSocketHandler<PATH, STORAGE>
    ): Locationed<PATH, WebSocketHandler<PATH, STORAGE>> {
        _handlers[path]?.let {
            throw DuplicateRegistrationError("Path $path already has a registered WebSocketHandler", it, handler)
        }

        _handlers[path] = handler

        return Locationed(path, handler)
    }
}

public class WebSocketTopicsBuilder {
    private val registry = MutablePathSpecMap<WebSocketTopic<*, *>>()
    public val registered: PathSpecMap<WebSocketTopic<*, *>> get() = registry

    public fun <PATH : PathSpec, STORAGE> register(
        path: PATH,
        topic: WebSocketTopic<PATH, STORAGE>
    ): Locationed<PATH, WebSocketTopic<PATH, STORAGE>> {
        registry[path]?.let {
            throw DuplicateRegistrationError("Path $path already has a registered WebSocketTopic", it, topic)
        }

        registry[path] = topic

        return Locationed(path, topic)
    }
}