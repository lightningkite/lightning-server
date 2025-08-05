package com.lightningkite.lightningserver

public interface WebSocketHandlerInterceptor {
    public operator fun <PATH: PathSpec, T> invoke(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T>

    public object None : WebSocketHandlerInterceptor {
        override fun <PATH: PathSpec, T> invoke(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> = handler
    }
}