package com.lightningkite.lightningserver.websockets

import com.lightningkite.lightningserver.OverrideOnly
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.instrument

/**
 * Wraps every logical socket: the one the client opened, and each virtual socket multiplexed inside
 * it. An implementation must therefore tolerate wrapping several handlers within one physical
 * connection.
 *
 * A concern tied to the connection itself — the origin check, transport-level policy — narrows itself
 * with [com.lightningkite.lightningserver.runtime.isRoot], which answers the same for all five phases
 * of one socket:
 *
 * ```kotlin
 * if (!serverRuntime.execution.isRoot()) return wrapped.willConnect(request)
 * ```
 */
public interface WebSocketInterceptor {
    public val name: String get() = this::class.simpleName ?: "anonymous"

    public fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T>

    /**
     * The compiled chain for "nothing installed" — it returns the handler untouched.
     */
    public object None : WebSocketInterceptor {
        override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
            handler
    }
}

/** One link of a compiled chain, wrapping every phase of [this] in its own instrumentation span. */
private fun <PATH : PathSpec, T> WebSocketHandler<PATH, T>.instrumented(name: String): WebSocketHandler<PATH, T> {
    return object : DelegatingWebSocketHandler<PATH, T>(this@instrumented) {
        @OverrideOnly
        context(serverRuntime: ServerRuntime)
        override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T =
            instrument(name) { wrapped.willConnect(request) }

        @OverrideOnly
        context(serverRuntime: ServerRuntime)
        override suspend fun didConnect(connection: WebSocketConnection<PATH, T>): Unit =
            instrument(name) { wrapped.didConnect(connection) }

        @OverrideOnly
        context(serverRuntime: ServerRuntime)
        override suspend fun messageFromClient(connection: WebSocketConnection<PATH, T>, frame: WebSocketFrame): Unit =
            instrument(name) { wrapped.messageFromClient(connection, frame) }

        @OverrideOnly
        context(serverRuntime: ServerRuntime)
        override suspend fun messageFromSubscription(
            connection: WebSocketConnection<PATH, T>,
            topic: WebSocketSubscriptionMessage<*, *>,
        ): Unit = instrument(name) { wrapped.messageFromSubscription(connection, topic) }

        @OverrideOnly
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
 */
internal fun List<WebSocketInterceptor>.compileAndInstrument(): WebSocketInterceptor {
    val effective = filter { it !== WebSocketInterceptor.None }
    if (effective.isEmpty()) return WebSocketInterceptor.None
    return effective.drop(1).fold(instrumentedLink(effective.first()), ::composeLinks)
}
