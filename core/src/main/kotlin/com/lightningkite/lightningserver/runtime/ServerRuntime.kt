package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.definition.Task
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.PathSpec1
import com.lightningkite.lightningserver.pathing.PathSpec2
import com.lightningkite.lightningserver.pathing.PathSpec3
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.lightningserver.websockets.WebSocketTopic

/**
 * An [Engine], running one execution on behalf of an [execution].
 *
 * An "execution" is one run of anything the server can run: an HTTP request, one WebSocket lifecycle
 * phase, a task, a schedule tick, a startup task, or a pre-deploy task. Everything an execution needs
 * beyond its attribution — settings, serialization, telemetry, task dispatch — is process-wide, so a
 * runtime is an engine plus one extra property.
 *
 * ## Why this is a type rather than a convention
 * Declaring `context(server: ServerRuntime)` says the work being done is attributable, and declaring
 * `context(engine: Engine)` says it is not. That makes "who initiated this?" a question the compiler
 * answers: work that must be audited cannot accidentally be written somewhere no initiator exists.
 *
 * Runtimes are minted by the framework at the seam every engine funnels through
 * ([com.lightningkite.lightningserver.runtime.execute]), never by user code.
 */
public interface ServerRuntime : Engine {
    /**
     * What started the execution this runtime is serving.
     *
     * ## Why it lives here rather than being passed as a parameter
     * Not a stylistic choice, and not a candidate for "simplification" into an argument. The sites
     * that must attribute their work are the two logical interceptors *and the inside of a handler
     * body*: disclosure auditing hangs off `emitTypedOutput`, which is called from within
     * `ApiHttpHandler` and `ApiWebSocketHandler`. Reaching that by parameter would mean adding one to
     * `HttpHandler.handle`, and so to every endpoint handler in the framework and in user code. The
     * runtime context is the only carrier already threaded to all three.
     */
    public val execution: Execution
}

/**
 * Provides access to the ServerRuntime instance from a context receiver.
 *
 * This allows nested functions to access the runtime without explicit parameter passing.
 */
context(runner: ServerRuntime)
public val serverRuntime: ServerRuntime get() = runner
