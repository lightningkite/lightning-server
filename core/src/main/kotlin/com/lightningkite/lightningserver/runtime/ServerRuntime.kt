package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.services.data.Unsafe
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.PathSpec2
import com.lightningkite.lightningserver.pathing.PathSpec3
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.WebSocketTopic
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryTrace
import com.lightningkite.services.telemetry.emptyTelemetryAttributes
import com.lightningkite.services.telemetry.telemetryTrace

/**
 * An [Engine] performing work.
 *
 * An [Engine] is the running server: its definition, settings, serialization and services, not doing
 * anything in particular. A `ServerRuntime` is that engine while it handles one [execution]: an HTTP
 * request, a WebSocket phase, a task, a schedule tick, or a startup or pre-deploy task. It knows what
 * the work is and what caused it.
 *
 * Which to require:
 * - `context(ServerRuntime)` for code that does work, such as using the database or other services,
 *   launching tasks, or sending messages. This is most user code, and handlers already run with one.
 * - `context(Engine)` for setup, and for helpers that only read shared state such as settings or
 *   serialization.
 *
 * A runtime is also an engine, so engine-level code can be called from anywhere.
 *
 * Runtimes are only created by the framework when it starts an execution, never by user code.
 */
@ConsistentCopyVisibility
public data class ServerRuntime private constructor(
    public val wrapped: Engine,
    // The execution lives on the runtime rather than being passed as a parameter because the sites that
    // must attribute their work include the inside of handler bodies (disclosure auditing hangs off
    // emitTypedOutput, called from ApiHttpHandler and ApiWebSocketHandler). Passing it would mean adding a
    // parameter to HttpHandler.handle and so to every handler; the runtime context already reaches them all.
    /** The work being performed, and what caused it. */
    public val execution: Execution,
) : Engine by wrapped {
    public val history: List<Execution> by lazy {
        (wrapped as? ServerRuntime)?.history.orEmpty() + execution
    }

    override fun toString(): String =
        "ServerRuntime(engine = ${processEngine::class.simpleName ?: processEngine.toString()}, executions = ${history.joinToString("->")})"

    public companion object {
        /**
         * Creates a runtime for [execution] on [engine]. Only the framework's execution seam should call this.
         *
         * **Do not use this. Use [Engine.execute]**
         * */
        @InternalLightningServerApi
        @Unsafe("The execution must follow the inheritance rules for executions, and anything run with the runtime must be wrapped in the server's execution interceptors.")
        @PublishedApi
        internal fun create(engine: Engine, execution: Execution): ServerRuntime = ServerRuntime(engine, execution)
    }
}

/**
 * Provides access to the ServerRuntime instance from a context receiver.
 *
 * This allows nested functions to access the runtime without explicit parameter passing.
 */
context(runner: ServerRuntime)
public val serverRuntime: ServerRuntime get() = runner


@InternalLightningServerApi
public suspend inline fun <T> Engine.execute(
    opName: String,
    scope: Execution,
    attributes: TelemetryAttributes = emptyTelemetryAttributes(),
    crossinline action: suspend ServerRuntime.(trace: TelemetryTrace) -> T
): T {
    @OptIn(Unsafe::class)
    // SAFETY: The created runtime is immediately used and wrapped in the execution interceptors
    return with(ServerRuntime.create(this, scope)) {
        telemetryTrace(opName, attributes) { trace ->
            server.compiledExecutionInterceptors.intercept { serverRuntime.action(trace) }
        }
    }
}

@InternalLightningServerApi
public suspend inline fun <T> Engine.executeWithoutTelemetry(
    scope: Execution,
    crossinline action: suspend ServerRuntime.() -> T
): T {
    @OptIn(Unsafe::class)
    // SAFETY: The created runtime is immediately used and wrapped in the execution interceptors
    return with(ServerRuntime.create(this, scope)) {
        server.compiledExecutionInterceptors.intercept { serverRuntime.action() }
    }
}
