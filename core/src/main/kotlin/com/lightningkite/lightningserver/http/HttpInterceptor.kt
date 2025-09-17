package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.instrument

public fun interface HttpInterceptor {
    public val name: String get() = this::class.simpleName ?: "anonymous"
    context(runtime: ServerRuntime)
    public suspend fun handle(request: HttpRequest<*>, cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse): HttpResponse

    public object None : HttpInterceptor {
        context(runtime: ServerRuntime)
        override suspend fun handle(
            request: HttpRequest<*>,
            cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse
        ): HttpResponse {
            return cont(request)
        }
    }
}

context(server: ServerRuntime) public suspend inline fun HttpInterceptor.handleInstrumented(request: HttpRequest<*>, noinline action: suspend ServerRuntime.(HttpRequest<*>) -> HttpResponse): HttpResponse {
    return instrument(name) {
        handle(request, action)
    }
}

internal fun List<HttpInterceptor>.compileAndInstrument(): HttpInterceptor = when(size) {
    0 -> HttpInterceptor.None
    else -> {
        val outermost = this[0]
        val start = HttpInterceptor { request, cont -> outermost.handleInstrumented(request, cont) }
        drop(0).fold(start) { earlierInterceptors, interceptor ->
            HttpInterceptor { request, cont -> earlierInterceptors.handle(request) { interceptor.handleInstrumented(it, cont) } }
        }
    }
}