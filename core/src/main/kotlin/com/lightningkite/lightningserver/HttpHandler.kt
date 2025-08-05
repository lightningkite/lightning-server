package com.lightningkite.lightningserver

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public interface HttpHandler<PATH : PathSpec> {
    public val timeout: Duration get() = 30.seconds
    public suspend fun handle(serverRunning: ServerRunning, request: HttpRequest<PATH>): HttpResponse
}

public fun <PATH : PathSpec> ServerDefinitionBuilder<*>.httpHandler(
    timeout: Duration = 30.seconds,
    handler: suspend ServerRunning.(HttpRequest<PATH>) -> HttpResponse
): HttpHandler<PATH> = object : HttpHandler<PATH> {
    override val timeout: Duration = timeout
    override suspend fun handle(serverRunning: ServerRunning, request: HttpRequest<PATH>): HttpResponse {
        return handler(serverRunning, request)
    }
}