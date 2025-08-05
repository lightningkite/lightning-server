package com.lightningkite.lightningserver

public interface HttpInterceptor {
    public suspend fun handle(serverRunning: ServerRunning, request: HttpRequest<*>, cont: suspend ServerRunning.(HttpRequest<*>) -> HttpResponse): HttpResponse

    public object None : HttpInterceptor {
        override suspend fun handle(serverRunning: ServerRunning, request: HttpRequest<*>, cont: suspend ServerRunning.(HttpRequest<*>) -> HttpResponse): HttpResponse = serverRunning.cont(request)
    }
}