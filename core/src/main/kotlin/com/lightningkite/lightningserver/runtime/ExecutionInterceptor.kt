package com.lightningkite.lightningserver.runtime

/**
 * Wraps one whole execution, whatever kind of execution it is.
 *
 * The HTTP and WebSocket interceptors each see one kind of work; this one sees every kind — an HTTP
 * request, each WebSocket lifecycle phase, a task, a schedule tick, a startup task, a pre-deploy
 * task. That uniformity is the reason it exists: a concern that must hold for *everything the server
 * runs* — a tenant context, a unit of work, a top-level audit record — cannot be expressed by
 * installing the same logic into three chains that between them still miss tasks and schedules.
 *
 * It wraps outside the HTTP and WebSocket chains, which run inside the execution this one wraps, and
 * inside the execution's own telemetry span, so that whatever an interceptor does is recorded as part
 * of the execution it belongs to rather than as a detached trace of its own.
 *
 * Install on a `ServerBuilder`; the first installed is the outermost, as with the other two chains.
 *
 * ```kotlin
 * object Server : ServerBuilder() {
 *     init {
 *         install(object : ExecutionInterceptor {
 *             override suspend fun <T> intercept(
 *                 runtime: ServerRuntime,
 *                 cont: suspend context(ServerRuntime) () -> T,
 *             ): T {
 *                 val start = TimeSource.Monotonic.markNow()
 *                 try { return with(runtime) { cont() } }
 *                 finally { println("${runtime.initiator} took ${start.elapsedNow()}") }
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
     * @param runtime The runtime for the execution being wrapped, naming what initiated it.
     * @param cont The continuation, invoked as `with(runtime) { cont() }`. Skipping it skips the
     *   execution entirely, so an interceptor that means to let the work happen must call it.
     */
    public suspend fun <T> intercept(
        runtime: ServerRuntime,
        cont: suspend context(ServerRuntime) () -> T,
    ): T

    /**
     * The compiled chain for "nothing installed" — it runs the execution untouched.
     *
     * Chain machinery, not something to install; a chain link around a pass-through would only cost a
     * span. [compileAndInstrument] drops these rather than wrapping them.
     */
    public object NoOp : ExecutionInterceptor {
        override suspend fun <T> intercept(
            runtime: ServerRuntime,
            cont: suspend context(ServerRuntime) () -> T,
        ): T = with(runtime) { cont() }
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
private suspend fun <T> ExecutionInterceptor.interceptInstrumented(
    runtime: ServerRuntime,
    cont: suspend context(ServerRuntime) () -> T,
): T = with(runtime) { instrument(name) { intercept(runtime, cont) } }

/** One link of a compiled chain, wrapping [interceptor] in its own instrumentation span. */
private fun instrumentedLink(interceptor: ExecutionInterceptor): ExecutionInterceptor =
    object : ExecutionInterceptor {
        override val name: String get() = interceptor.name

        override suspend fun <T> intercept(
            runtime: ServerRuntime,
            cont: suspend context(ServerRuntime) () -> T,
        ): T = interceptor.interceptInstrumented(runtime, cont)
    }

/** Nests [inner] inside [outer], so [outer] runs first and can post-process what [inner] returns. */
private fun composeLinks(outer: ExecutionInterceptor, inner: ExecutionInterceptor): ExecutionInterceptor =
    object : ExecutionInterceptor {
        override val name: String get() = "${outer.name} -> ${inner.name}"

        override suspend fun <T> intercept(
            runtime: ServerRuntime,
            cont: suspend context(ServerRuntime) () -> T,
        ): T = outer.intercept(runtime) { inner.interceptInstrumented(serverRuntime, cont) }
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
