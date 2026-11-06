package com.lightningkite.lightningserver.runtime

/**
 * Wraps every execution the server runs, whatever its kind.
 *
 * An execution is an HTTP request, a WebSocket phase, a task, a schedule tick, or a startup or
 * pre-deploy task. The [Invocation] says which kind it is and carries the request, if there is one.
 *
 * [HttpInterceptor][com.lightningkite.lightningserver.http.HttpInterceptor] and
 * [WebSocketInterceptor][com.lightningkite.lightningserver.websockets.WebSocketInterceptor] only see
 * requests and sockets the engine actually received. This one also sees `/meta/bulk` sub-requests,
 * internal endpoint calls, and multiplexed virtual sockets.
 *
 * It runs outside the HTTP and WebSocket chains, and inside the execution's telemetry span.
 *
 * If it throws during an HTTP execution, the server's exception handler turns the exception into the
 * response. An [HttpStatusException][com.lightningkite.lightningserver.HttpStatusException] keeps its
 * status, so a 429 stays a 429. Interceptors installed outside this one see the exception, not the
 * response. If it throws during any other kind of execution, that execution fails.
 *
 * For a request the engine received, when it throws matters. Before calling `cont`, the error
 * response still goes through the HTTP chain and gets CORS and security headers. After `cont`, the
 * HTTP chain has already run, so the response goes out without them. Reject before calling `cont`.
 *
 * Install it on a `ServerBuilder`. The first one installed is the outermost, like the other chains.
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
     * The name shown in traces and debugging.
     *
     * Defaults to the simple class name, or "anonymous" if the class has none.
     */
    public val name: String get() = this::class.simpleName ?: "anonymous"

    /**
     * Wraps [cont], which is the rest of the chain and then the execution itself.
     *
     * [invocation] describes the same execution as the runtime in context. If [cont] is not called,
     * the execution does not run at all.
     */
    context(runtime: ServerRuntime)
    public suspend fun <T> intercept(
        invocation: Invocation,
        cont: suspend context(ServerRuntime) () -> T,
    ): T

    /**
     * Stands for "no interceptors installed" and just runs the execution.
     *
     * Do not install this. [compileAndInstrument] drops it.
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
 * Calls [ExecutionInterceptor.intercept] inside its own span, so each interceptor's time shows up separately.
 *
 * Unlike the HTTP version, this does not catch exceptions. There is no generic response to make here,
 * so failures go to the handling for that kind of execution.
 */
context(runtime: ServerRuntime)
private suspend fun <T> ExecutionInterceptor.interceptInstrumented(
    invocation: Invocation,
    cont: suspend context(ServerRuntime) () -> T,
): T = instrument(name) { intercept(invocation, cont) }

/** Wraps [interceptor] so that it runs inside its own span. */
private fun instrumentedLink(interceptor: ExecutionInterceptor): ExecutionInterceptor =
    object : ExecutionInterceptor {
        override val name: String get() = interceptor.name

        context(runtime: ServerRuntime)
        override suspend fun <T> intercept(
            invocation: Invocation,
            cont: suspend context(ServerRuntime) () -> T,
        ): T = interceptor.interceptInstrumented(invocation, cont)
    }

/** Returns an interceptor that runs [outer] around [inner]. */
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
 * Combines this list into one interceptor, giving each its own span.
 *
 * The first interceptor is the outermost, like the HTTP and WebSocket chains.
 * [ExecutionInterceptor.NoOp] entries are dropped.
 */
internal fun List<ExecutionInterceptor>.compileAndInstrument(): ExecutionInterceptor {
    val effective = filter { it !== ExecutionInterceptor.NoOp }
    if (effective.isEmpty()) return ExecutionInterceptor.NoOp
    return effective.drop(1).fold(instrumentedLink(effective.first()), ::composeLinks)
}
