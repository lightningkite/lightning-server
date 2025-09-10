package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.definition.builder.DuplicateRegistrationError
import com.lightningkite.lightningserver.pathing.MutablePathSpecMap
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpecMap

public class HttpBuilder {
    public val interceptors: HttpInterceptor.Builder = HttpInterceptor.Builder()

    private val _handlers = MutablePathSpecMap<HashMap<HttpMethod, HttpHandler<*>>>()
    public val handlers: PathSpecMap<Map<HttpMethod, HttpHandler<*>>> get() = _handlers

    public fun <PATH : PathSpec> register(
        endpoint: HttpEndpoint<PATH>,
        handler: HttpHandler<PATH>
    ) {
        val methodsMap = _handlers.getOrPut(endpoint.path, ::HashMap)

        methodsMap[endpoint.method]?.let {
            throw DuplicateRegistrationError("Path ${endpoint.path} has already registered an endpoint for ${endpoint.method}.", it, handler)
        }

        methodsMap[endpoint.method] = handler
    }
}