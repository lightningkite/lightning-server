package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.http.HttpRequest

/**
 * Wraps one whole execution, whatever kind of execution it is.
 *
 * This is the chain for logical concerns: it sees every execution the server runs — an HTTP request,
 * each WebSocket lifecycle phase, a task, a schedule tick, a startup task, a pre-deploy task — including
 * the `/meta/bulk` sub-requests, internal endpoint calls and multiplexed virtual sockets that the
 * [com.lightningkite.lightningserver.http.HttpInterceptor] and
 * [com.lightningkite.lightningserver.websockets.WebSocketInterceptor] chains never see. The [Invocation]
 * names the kind of execution and carries its request, if it has one.
 *
 * It wraps outside the HTTP and WebSocket chains, which run inside the execution this one wraps, and
 * inside the execution's own telemetry span. An exception thrown here therefore skips those chains,
 * so a rejection that must still get CORS or security headers belongs in an `HttpInterceptor`.
 *
 * Install on a `ServerBuilder`; the first installed is the outermost, as with the other two chains.
 *
 * ```kotlin
 * object Server : ServerBuilder() {
 *     init {
 *         install(object : ExecutionInterceptor {
 *             context(runtime: ServerRuntime)
 *             override suspend fun <T> intercept(
 *                 invocation: Invocation,
 *                 cont: suspend context(ServerRuntime) () -> T,
 *             ): T {
 *                 val start = TimeSource.Monotonic.markNow()
 *                 try { return cont() }
 *                 finally { println("${invocation.execution} took ${start.elapsedNow()}") }
 *             }
 *         })
 *     }
 * }
 * ```
 */
public interface ExecutionInterceptor {
    /**
     * The name of this interceptor, used for instrumentation and debugging.
     * Defaults to the simple class name or "anonymous" for lambdas.
     */
    public val name: String get() = this::class.simpleName ?: "anonymous"

    /**
     * Wraps [cont], which is the rest of the chain and then the execution itself.
     *
     * [invocation] holds the same execution as the runtime in context. Skipping [cont] skips the
     * execution entirely, so an interceptor that means to let the work happen must call it.
     */
    context(runtime: ServerRuntime)
    public suspend fun <T> intercept(
        invocation: Invocation,
        cont: suspend context(ServerRuntime) () -> T,
    ): T

    /**
     * The compiled chain for "nothing installed" — it runs the execution untouched.
     *
     * Chain machinery, not something to install; a chain link around a pass-through would only cost a
     * span. [compileAndInstrument] drops these rather than wrapping them.
     */
    public object NoOp : ExecutionInterceptor {
        context(runtime: ServerRuntime)
        override suspend fun <T> intercept(
            invocation: Invocation,
            cont: suspend context(ServerRuntime) () -> T,
        ): T = cont()
    }
}

/**
 * Wraps the intercept call in its own instrumentation span, so time spent in each interceptor is
 * attributable.
 *
 * Unlike the HTTP counterpart this does not recover from exceptions: an execution's result type is
 * whatever that kind of execution returns, so there is no response to fabricate here. Failures
 * propagate to the kind-specific handling at the seam.
 */
context(runtime: ServerRuntime)
private suspend fun <T> ExecutionInterceptor.interceptInstrumented(
    invocation: Invocation,
    cont: suspend context(ServerRuntime) () -> T,
): T = instrument(name) { intercept(invocation, cont) }

/** One link of a compiled chain, wrapping [interceptor] in its own instrumentation span. */
private fun instrumentedLink(interceptor: ExecutionInterceptor): ExecutionInterceptor =
    object : ExecutionInterceptor {
        override val name: String get() = interceptor.name

        context(runtime: ServerRuntime)
        override suspend fun <T> intercept(
            invocation: Invocation,
            cont: suspend context(ServerRuntime) () -> T,
        ): T = interceptor.interceptInstrumented(invocation, cont)
    }

/** Nests [inner] inside [outer], so [outer] runs first and can post-process what [inner] returns. */
private fun composeLinks(outer: ExecutionInterceptor, inner: ExecutionInterceptor): ExecutionInterceptor =
    object : ExecutionInterceptor {
        override val name: String get() = "${outer.name} -> ${inner.name}"

        context(runtime: ServerRuntime)
        override suspend fun <T> intercept(
            invocation: Invocation,
            cont: suspend context(ServerRuntime) () -> T,
        ): T = outer.intercept(invocation) { inner.interceptInstrumented(invocation, cont) }
    }

/**
 * Compiles a list of interceptors into a single chained interceptor with instrumentation.
 *
 * The first interceptor in the list executes first and so wraps everything the rest of the chain
 * wraps, matching the HTTP and WebSocket chains. [ExecutionInterceptor.NoOp] entries are dropped
 * rather than wrapped, since a chain link around a pass-through only costs a span.
 */
internal fun List<ExecutionInterceptor>.compileAndInstrument(): ExecutionInterceptor {
    val effective = filter { it !== ExecutionInterceptor.NoOp }
    if (effective.isEmpty()) return ExecutionInterceptor.NoOp
    return effective.drop(1).fold(instrumentedLink(effective.first()), ::composeLinks)
}
