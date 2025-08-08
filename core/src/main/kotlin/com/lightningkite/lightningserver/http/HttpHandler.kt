package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.pathing.PathSpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public interface HttpHandler<PATH : PathSpec> {
    public val timeout: Duration get() = 30.seconds
    public suspend fun handle(serverRuntime: ServerRuntime, request: HttpRequest<PATH>): HttpResponse
}

public fun <PATH : PathSpec> HttpHandler(
    timeout: Duration = 30.seconds,
    handler: suspend ServerRuntime.(HttpRequest<PATH>) -> HttpResponse
): HttpHandler<PATH> = object : HttpHandler<PATH> {
    override val timeout: Duration = timeout
    override suspend fun handle(serverRuntime: ServerRuntime, request: HttpRequest<PATH>): HttpResponse {
        return handler(serverRuntime, request)
    }
}