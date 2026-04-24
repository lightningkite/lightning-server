package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.instrument
import kotlinx.serialization.KSerializer

public interface WebSocketHandlerInterceptor {
    public val name: String get() = this::class.simpleName ?: "anonymous"

    public fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T>

    public object None : WebSocketHandlerInterceptor {
        override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
            handler
    }
}


private fun <PATH : PathSpec, T> WebSocketHandler<PATH, T>.instrumented(name: String): WebSocketHandler<PATH, T> {
    return object : WebSocketHandler<PATH, T> {
        override val storageSerializer: KSerializer<T>
            get() = this@instrumented.storageSerializer

        context(serverRuntime: ServerRuntime)
        override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
            return instrument(name) {
                this@instrumented.willConnect(request)
            }
        }

        context(connection: WebSocketConnection<PATH, T>)
        override suspend fun didConnect() {
            return instrument(name) {
                this@instrumented.didConnect()
            }
        }

        context(connection: WebSocketConnection<PATH, T>)
        override suspend fun messageFromClient(frame: WebSocketFrame) {
            return instrument(name) {
                this@instrumented.messageFromClient(frame)
            }
        }

        context(connection: WebSocketConnection<PATH, T>)
        override suspend fun messageFromSubscription(topic: WebSocketSubscriptionMessage<*, *>) {
            return instrument(name) {
                this@instrumented.messageFromSubscription(topic)
            }
        }

        context(connection: WebSocketConnection<PATH, T>)
        override suspend fun disconnect(reason: WebSocketClose) {
            return instrument(name) {
                this@instrumented.disconnect(reason)
            }
        }

    }
}

internal fun List<WebSocketHandlerInterceptor>.compileAndInstrument(): WebSocketHandlerInterceptor {
    return when (size) {
        0 -> WebSocketHandlerInterceptor.None
        1 -> {
            val one = this[0]
            object : WebSocketHandlerInterceptor {
                override val name: String
                    get() = one.name

                override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> {
                    return one.intercept(handler).instrumented(one.name)
                }
            }
        }

        else -> {
            reduceRightOrNull { laterInterceptors, interceptor ->
                object : WebSocketHandlerInterceptor {
                    override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> {
                        return laterInterceptors.intercept(
                            interceptor.intercept(handler).instrumented(interceptor.name)
                        )
                    }
                }
            } ?: WebSocketHandlerInterceptor.None
        }
    }
}
