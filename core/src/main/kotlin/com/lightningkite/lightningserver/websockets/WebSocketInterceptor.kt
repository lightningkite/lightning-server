package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.instrument
import kotlinx.serialization.KSerializer

/**
 * Shared contract for WebSocket interceptors.
 *
 * Not installable on its own: an interceptor is either a [WebSocketConnectionInterceptor] or a
 * [WebSocketLogicalInterceptor], and which one it is decides whether it sees virtual sockets. This
 * mirrors the [com.lightningkite.lightningserver.http.HttpConnectionInterceptor] /
 * [com.lightningkite.lightningserver.http.HttpLogicalInterceptor] split on the HTTP side, for the
 * same reason: multiplexing means one physical connection can carry many logical ones.
 */
public interface WebSocketInterceptor {
    public val name: String get() = this::class.simpleName ?: "anonymous"

    public fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T>

    /**
     * The compiled chain for "nothing installed" — it returns the handler untouched.
     *
     * Chain machinery, not something to install; a no-op of either kind would only cost a span.
     */
    public object None : WebSocketInterceptor {
        override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
            handler
    }
}

/**
 * An interceptor that wraps the physical WebSocket connection, once per socket the client opened.
 *
 * Correct for concerns tied to the connection itself — the origin check, transport-level policy —
 * where running again for each virtual socket inside a multiplexed connection would repeat a decision
 * that was already made about the one real socket.
 *
 * @see WebSocketLogicalInterceptor for the per-logical-socket counterpart.
 */
public interface WebSocketConnectionInterceptor : WebSocketInterceptor

/**
 * An interceptor that wraps every logical socket: the one the client opened, and each virtual socket
 * multiplexed inside it.
 *
 * Correct for concerns that describe what is actually being done — access logging, auditing, rate
 * limiting — where seeing only the physical connection would report a single socket no matter how
 * many independent subscriptions were running over it.
 *
 * An implementation must tolerate wrapping several handlers within one physical connection, and
 * should attribute its work to the connection's own
 * [com.lightningkite.lightningserver.websockets.WebSocketConnectRequest.requestId] rather than
 * assuming one socket per client.
 *
 * @see WebSocketConnectionInterceptor for the per-physical-connection counterpart.
 */
public interface WebSocketLogicalInterceptor : WebSocketInterceptor


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

internal fun List<WebSocketInterceptor>.compileAndInstrument(): WebSocketInterceptor {
    return when (size) {
        0 -> WebSocketInterceptor.None
        1 -> {
            val one = this[0]
            object : WebSocketInterceptor {
                override val name: String
                    get() = one.name

                override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> {
                    return one.intercept(handler).instrumented(one.name)
                }
            }
        }

        else -> {
            reduceRightOrNull { laterInterceptors, interceptor ->
                object : WebSocketInterceptor {
                    override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> {
                        return laterInterceptors.intercept(
                            interceptor.intercept(handler).instrumented(interceptor.name)
                        )
                    }
                }
            } ?: WebSocketInterceptor.None
        }
    }
}
