package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.ServerRuntime

public interface HttpInterceptor {
    public suspend fun handle(serverRuntime: ServerRuntime, request: HttpRequest<*>, cont: suspend ServerRuntime.(HttpRequest<*>) -> HttpResponse): HttpResponse

    public object None : HttpInterceptor {
        override suspend fun handle(serverRuntime: ServerRuntime, request: HttpRequest<*>, cont: suspend ServerRuntime.(HttpRequest<*>) -> HttpResponse): HttpResponse = serverRuntime.cont(request)
    }
}

context(server: ServerRuntime)
public suspend fun HttpInterceptor.handle(request: HttpRequest<*>, action: suspend ServerRuntime.(HttpRequest<*>) -> HttpResponse): HttpResponse =
    handle(server, request, action)


public class HttpInterceptors(interceptors: List<HttpInterceptor> = emptyList()) : HttpInterceptor {
    /**
     * Combines two interceptors.
     * The original continuation is handled last, since these are "intercepting"
     *
     * Order of operations: [first] -> [second] -> `cont`
     * */
    private data class Combine(
        val first: HttpInterceptor,
        val second: HttpInterceptor
    ) : HttpInterceptor {
        // WARNING: This will melt your brain
        override suspend fun handle(
            serverRuntime: ServerRuntime,
            request: HttpRequest<*>,
            cont: suspend ServerRuntime.(HttpRequest<*>) -> HttpResponse
        ): HttpResponse =
            first.handle(serverRuntime, request) { second.handle(serverRuntime, it, cont) }
    }

    private fun HttpInterceptor.then(other: HttpInterceptor) = when {
        this === HttpInterceptor.None -> other
        other === HttpInterceptor.None -> this
        else -> Combine(this, other)
    }

    private var fullInterceptor: HttpInterceptor =
        interceptors
            .reduceOrNull { total, nextInterceptor -> total.then(nextInterceptor) }
            ?: HttpInterceptor.None

    private var _interceptors = ArrayList(interceptors)
    public val interceptors: List<HttpInterceptor> get() = _interceptors

    override suspend fun handle(serverRuntime: ServerRuntime, request: HttpRequest<*>, cont: suspend ServerRuntime.(HttpRequest<*>) -> HttpResponse): HttpResponse =
        fullInterceptor.handle(serverRuntime, request, cont)


    /**
     * Adds the provided [interceptor] to the end of the interception list.
     * */
    public fun register(interceptor: HttpInterceptor) {
        fullInterceptor = fullInterceptor.then(interceptor)
        _interceptors.add(interceptor)
    }

    /**
     * Adds the provided [interceptor] to the end of the interception list.
     * */
    public operator fun plusAssign(interceptor: HttpInterceptor) { register(interceptor) }
}