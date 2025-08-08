package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.ServerRuntime
import com.lightningkite.lightningserver.pathing.PathSpec

public interface HttpInterceptor {
    public suspend fun handle(serverRuntime: ServerRuntime, request: HttpRequest<*>, cont: suspend ServerRuntime.(HttpRequest<*>) -> HttpResponse): HttpResponse

    public object None : HttpInterceptor {
        override suspend fun handle(serverRuntime: ServerRuntime, request: HttpRequest<*>, cont: suspend ServerRuntime.(HttpRequest<*>) -> HttpResponse): HttpResponse = serverRuntime.cont(request)
    }

    public class Builder(
        interceptors: List<HttpInterceptor> = emptyList()
    ) {
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
            override suspend fun handle(
                serverRuntime: ServerRuntime,
                request: HttpRequest<*>,
                cont: suspend ServerRuntime.(HttpRequest<*>) -> HttpResponse
            ): HttpResponse =
                first.handle(serverRuntime, request) { second.handle(serverRuntime, it, cont) }
        }

        private fun HttpInterceptor.then(other: HttpInterceptor) = when {
            this === None -> other
            other === None -> this
            else -> Combine(this, other)
        }

        private val _interceptors = ArrayList(interceptors)
        public val interceptors: List<HttpInterceptor> get() = _interceptors

        private var fullInterceptor: HttpInterceptor =
            interceptors
                .reduceOrNull { acc, interceptor -> acc.then(interceptor) }
                ?: None

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

        public fun build(): HttpInterceptor = fullInterceptor
    }
}

context(server: ServerRuntime)
public suspend fun HttpInterceptor.handle(request: HttpRequest<*>, action: suspend ServerRuntime.(HttpRequest<*>) -> HttpResponse): HttpResponse =
    handle(server, request, action)

private data class InterceptedHandler<PATH : PathSpec>(
    val handler: HttpHandler<PATH>,
    val interceptor: HttpInterceptor
) : HttpHandler<PATH> {
    override suspend fun handle(serverRuntime: ServerRuntime, request: HttpRequest<PATH>): HttpResponse =
        interceptor.handle(serverRuntime, request) { handler.handle(serverRuntime, request) }
}

public fun <PATH : PathSpec> HttpInterceptor.intercept(handler: HttpHandler<PATH>): HttpHandler<PATH> = InterceptedHandler(handler, this)