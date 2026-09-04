package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.instrument

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
 * should attribute its work to the socket's own
 * [com.lightningkite.lightningserver.runtime.Initiator.WebSocket.socketId] rather than assuming one
 * socket per client.
 *
 * @see WebSocketConnectionInterceptor for the per-physical-connection counterpart.
 */
public interface WebSocketLogicalInterceptor : WebSocketInterceptor


/** One link of a compiled chain, wrapping every phase of [this] in its own instrumentation span. */
private fun <PATH : PathSpec, T> WebSocketHandler<PATH, T>.instrumented(name: String): WebSocketHandler<PATH, T> {
    return object : DelegatingWebSocketHandler<PATH, T>(this@instrumented) {
        context(serverRuntime: ServerRuntime)
        override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T =
            instrument(name) { wrapped.willConnect(request) }

        context(serverRuntime: ServerRuntime)
        override suspend fun didConnect(connection: WebSocketConnection<PATH, T>): Unit =
            instrument(name) { wrapped.didConnect(connection) }

        context(serverRuntime: ServerRuntime)
        override suspend fun messageFromClient(connection: WebSocketConnection<PATH, T>, frame: WebSocketFrame): Unit =
            instrument(name) { wrapped.messageFromClient(connection, frame) }

        context(serverRuntime: ServerRuntime)
        override suspend fun messageFromSubscription(
            connection: WebSocketConnection<PATH, T>,
            topic: WebSocketSubscriptionMessage<*, *>,
        ): Unit = instrument(name) { wrapped.messageFromSubscription(connection, topic) }

        context(serverRuntime: ServerRuntime)
        override suspend fun disconnect(connection: WebSocketConnection<PATH, T>, reason: WebSocketClose): Unit =
            instrument(name) { wrapped.disconnect(connection, reason) }
    }
}

/** One link of a compiled chain, wrapping [interceptor] so what it produces is instrumented under its name. */
private fun instrumentedLink(interceptor: WebSocketInterceptor): WebSocketInterceptor = object : WebSocketInterceptor {
    override val name: String get() = interceptor.name

    override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
        interceptor.intercept(handler).instrumented(interceptor.name)
}

/** Nests [inner] inside [outer], so [outer] wraps the handler [inner] already wrapped. */
private fun composeLinks(outer: WebSocketInterceptor, inner: WebSocketInterceptor): WebSocketInterceptor =
    object : WebSocketInterceptor {
        override val name: String get() = "${outer.name} -> ${inner.name}"

        override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
            outer.intercept(inner.intercept(handler).instrumented(inner.name))
    }

/**
 * Compiles a list of interceptors into a single chained interceptor with instrumentation.
 *
 * The first interceptor in the list is outermost, so it sees a connection first and wraps everything
 * the rest of the chain wrapped. [WebSocketInterceptor.None] entries are dropped rather than wrapped,
 * since a chain link around a pass-through only costs a span.
 *
 * Accepts any list of interceptors so the same machinery serves both the connection-scoped and the
 * logical-socket-scoped chain; which interceptors reach which chain is settled at installation by
 * their type.
 */
internal fun List<WebSocketInterceptor>.compileAndInstrument(): WebSocketInterceptor {
    val effective = filter { it !== WebSocketInterceptor.None }
    if (effective.isEmpty()) return WebSocketInterceptor.None
    return effective.drop(1).fold(instrumentedLink(effective.first()), ::composeLinks)
}
