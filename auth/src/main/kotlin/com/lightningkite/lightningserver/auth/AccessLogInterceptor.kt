package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.ExecutionInterceptor
import com.lightningkite.lightningserver.runtime.Invocation
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.logicalId
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

/**
 * Writes one access-log line per HTTP request and per WebSocket open and close, naming the resolved
 * principal.
 *
 * Install it in your `ServerBuilder`:
 * ```kotlin
 * init { install(AccessLogInterceptor()) }
 * ```
 *
 * Lines are logged at INFO, and the whole interceptor is skipped when INFO logging is off:
 * ```
 * /widgets accessed by user@example.com (10.0.0.1) -> 200 in 12ms [req a1b2…]
 * ws /widgets/updates opened by user@example.com (10.0.0.1) [conn c3d4…]
 * ws /widgets/updates closed by user@example.com (10.0.0.1) [conn c3d4…]
 * ```
 * `<principal>` is the resolved [Authentication] — rendered including masquerade as "actor
 * masquerading as target" — or `anonymous` when the request carries no credentials.
 *
 * ## What gets a line
 * Every HTTP execution gets a line: each part of a `/meta/bulk` call and each internal endpoint call
 * gets its own, carrying its own request ID and the ID of the execution that dispatched it. The outcome
 * is the response status, `ok` for an internal call that returned, or the status of the
 * [HttpStatusException] it threw. WebSocket connections are logged at open and close, including the
 * virtual sockets inside a multiplexed connection.
 *
 * ## Failures
 * HTTP lines are emitted after the request finishes, so a request that threw is logged as `failed`
 * rather than silently dropped. Auth resolution is cached per request, so naming the principal costs
 * nothing when a handler resolves auth anyway, and a resolution failure (e.g. a malformed token) is
 * swallowed here so logging never breaks a request.
 */
public class AccessLogInterceptor : ExecutionInterceptor {
    override val name: String = "AccessLog"

    context(runtime: ServerRuntime)
    override suspend fun <T> intercept(invocation: Invocation, cont: suspend context(ServerRuntime) () -> T): T {
        if (!runtime.logger.isInfoEnabled()) return cont()

        return when (invocation) {
            is Invocation.Http -> {
                val request = invocation.request
                val started = TimeSource.Monotonic.markNow()
                var outcome = "failed"
                try {
                    // Claude: a routed request returns its response; an internal typed call returns its output.
                    cont().also { outcome = (it as? HttpResponse)?.status?.code?.toString() ?: "ok" }
                } catch (e: HttpStatusException) {
                    outcome = e.status.code.toString()
                    throw e
                } finally {
                    // In a finally so a request that threw still produces a line. Resolved outside the logging
                    // lambda, which is not suspending; by now auth is cached, so this costs nothing.
                    val principal = request.authString()
                    val elapsedMs = started.elapsedNow().inWholeMilliseconds
                    runtime.logger.info {
                        "${request.path} accessed by $principal (${request.sourceIp}) " +
                            "-> $outcome in ${elapsedMs}ms ${idSuffix()}"
                    }
                }
            }

            is Invocation.WebSocket -> {
                val request = invocation.request
                val event = when (invocation.execution.phase) {
                    Execution.WebSocket.Phase.Connect -> "opened"
                    Execution.WebSocket.Phase.Disconnect -> "closed"
                    else -> null
                }
                if (event != null) {
                    val principal = request.authString()
                    runtime.logger.info {
                        "ws ${request.path} $event by $principal (${request.sourceIp}) ${idSuffix(idLabel = "conn")}"
                    }
                }
                cont()
            }

            is Invocation.Unrequested -> cont()
        }
    }
}

/**
 * The resolved principal for this request, or `anonymous`.
 *
 * A resolution failure is swallowed: the access log must never be the reason a request fails, and the
 * handler surfaces the real error itself.
 */
context(runtime: ServerRuntime)
private suspend fun com.lightningkite.lightningserver.data.Request<*>.authString(): String = try {
    this[Authentication.CacheKey]?.let{if(generalSettings().debug) it.toDebugString() else it.toString()} ?: "anonymous"
} catch (e: CancellationException) {
    throw e // never swallow cancellation — it would break structured concurrency
} catch (_: Exception) {
    "anonymous"
}

/**
 * Renders the line's correlation id, plus `of <parent>` when this execution was genuinely dispatched
 * from within another one (a sub-request, a virtual socket) — omitted when there is no parent.
 */
context(runtime: ServerRuntime)
private fun idSuffix(idLabel: String = "req"): String {
    val initiator = runtime.execution
    val id = initiator.logicalId
    return initiator.parent?.takeIf { it != id }?.let { "[$idLabel $id of $it]" } ?: "[$idLabel $id]"
}
