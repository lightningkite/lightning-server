package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.instrument

public fun interface HttpInterceptor {
    public val name: String get() = this::class.simpleName ?: "anonymous"

    context(runtime: ServerRuntime)
    public suspend fun intercept(request: HttpRequest<*>, cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse): HttpResponse

    public object None : HttpInterceptor {
        context(runtime: ServerRuntime)
        override suspend fun intercept(
            request: HttpRequest<*>,
            cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse
        ): HttpResponse {
            return cont(request)
        }
    }
}

context(server: ServerRuntime)
public suspend inline fun HttpInterceptor.interceptInstrumented(request: HttpRequest<*>, noinline action: suspend ServerRuntime.(HttpRequest<*>) -> HttpResponse): HttpResponse {
    return instrument(name) {
        intercept(request, action)
    }
}

internal fun List<HttpInterceptor>.compileAndInstrument(): HttpInterceptor = when (size) {
    0 -> HttpInterceptor.None
    1 -> HttpInterceptor { request, cont -> first().interceptInstrumented(request, cont) }
    else -> reduceIndexed { idx, acc, interceptor ->
        when {
            acc === HttpInterceptor.None -> interceptor
            interceptor === HttpInterceptor.None -> acc

            else -> HttpInterceptor { request, cont ->
                // idx is of the current interceptor in the list, so will start at 1
                if (idx == 1) acc.interceptInstrumented(request) { interceptor.interceptInstrumented(it, cont) }
                else acc.intercept(request) { interceptor.interceptInstrumented(it, cont) }
            }
        }
    }
}