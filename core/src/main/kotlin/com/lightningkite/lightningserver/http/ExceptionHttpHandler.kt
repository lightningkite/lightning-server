package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.ServerRuntime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


public interface ExceptionHttpHandler {
    public val timeout: Duration get() = 30.seconds

    context(server: ServerRuntime)
    public suspend fun handle(request: HttpRequest<PathSpec>, exception: Exception): HttpResponse

}

