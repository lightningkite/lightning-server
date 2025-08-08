package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.pathing.PathSpec

public interface WebSocketHandlerInterceptor {
    public operator fun <PATH: PathSpec, T> invoke(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T>

    public object None : WebSocketHandlerInterceptor {
        override fun <PATH: PathSpec, T> invoke(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> = handler
    }

    public class Builder(
        interceptors: List<WebSocketHandlerInterceptor> = emptyList()
    ) {
        private data class Combine(
            val first: WebSocketHandlerInterceptor,
            val second: WebSocketHandlerInterceptor
        ) : WebSocketHandlerInterceptor {
            override fun <PATH : PathSpec, T> invoke(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> = second(first(handler))
        }

        private fun WebSocketHandlerInterceptor.then(other: WebSocketHandlerInterceptor) = when {
            this === None -> other
            other === None -> this
            else -> Combine(this, other)
        }

        private val _interceptors = ArrayList(interceptors)
        public val interceptors: List<WebSocketHandlerInterceptor> get() = _interceptors

        /**
         * Adds the provided [interceptor] to the end of the interception list.
         * */
        public fun register(interceptor: WebSocketHandlerInterceptor) {
            _interceptors.add(interceptor)
        }

        /**
         * Adds the provided [interceptor] to the end of the interception list.
         * */
        public operator fun plusAssign(interceptor: WebSocketHandlerInterceptor) { register(interceptor) }

        public fun build(): WebSocketHandlerInterceptor =
            interceptors.reduceOrNull { acc, interceptor -> acc.then(interceptor) } ?: None
    }
}