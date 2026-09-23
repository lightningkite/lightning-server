package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpInterceptor
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.logicalId
import com.lightningkite.lightningserver.websockets.DelegatingWebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketClose.Code
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketConnection
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketInterceptor
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

/**
 * Writes one access-log line per logical request and per WebSocket connection, naming the resolved
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
 * ws /widgets/updates closed by user@example.com (10.0.0.1) -> VIOLATED_POLICY after 34s [conn c3d4…]
 * ```
 * `<principal>` is the resolved [Authentication] — rendered including masquerade as "actor
 * masquerading as target" — or `anonymous` when the request carries no credentials.
 *
 * ## What gets a line
 * A multiplexed request such as `/meta/bulk` produces a line per sub-request rather than one line for
 * the batch, each carrying its own request ID and the ID of the request that carried it. WebSocket
 * connections are logged at open and close, including the virtual sockets inside a multiplexed
 * connection.
 *
 * ## Failures
 * HTTP lines are emitted after the handler returns, so they carry the outcome — including when the
 * handler threw, which is logged as `failed` rather than being silently dropped. Auth resolution is
 * cached per request, so naming the principal costs nothing when a handler resolves auth anyway, and
 * a resolution failure (e.g. a malformed token) is swallowed here so logging never breaks a request.
 */
public class AccessLogInterceptor : HttpInterceptor, WebSocketInterceptor {
    override val name: String = "AccessLog"

    context(runtime: ServerRuntime)
    override suspend fun intercept(
        request: HttpRequest<*>,
        cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse,
    ): HttpResponse {
        if (!runtime.logger.isInfoEnabled()) return cont(request)
        val started = TimeSource.Monotonic.markNow()
        var outcome: String = "failed"
        try {
            return cont(request).also { outcome = it.status.code.toString() }
        } finally {
            // In a finally so a handler that threw still produces a line: an access log with silent
            // gaps is worse than one that records the failure. Resolved outside the logging lambda,
            // which is not suspending; by now auth is cached, so this costs nothing.
            val principal = request.authString()
            val elapsedMs = started.elapsedNow().inWholeMilliseconds
            runtime.logger.info {
                "${request.path} accessed by $principal (${request.sourceIp}) " +
                    "-> $outcome in ${elapsedMs}ms ${idSuffix()}"
            }
        }
    }

    override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
        object : DelegatingWebSocketHandler<PATH, T>(handler) {
            context(serverRuntime: ServerRuntime)
            override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                if (serverRuntime.logger.isInfoEnabled()) {
                    val principal = request.authString()
                    serverRuntime.logger.info {
                        "ws ${request.path} opened by $principal (${request.sourceIp}) " +
                            idSuffix(idLabel = "conn")
                    }
                }
                return wrapped.willConnect(request)
            }

            context(serverRuntime: ServerRuntime)
            override suspend fun disconnect(connection: WebSocketConnection<PATH, T>, reason: WebSocketCloseReason.Code) {
                if (serverRuntime.logger.isInfoEnabled()) {
                    val request = connection.request
                    val principal = request.authString()
                    serverRuntime.logger.info {
                        "ws ${request.path} closed by $principal (${request.sourceIp}) " +
                            "-> $reason ${idSuffix(idLabel = "conn")}"
                    }
                }
                wrapped.disconnect(connection, reason)
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
    this[Authentication.CacheKey]?.toString() ?: "anonymous"
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
    return initiator.causedBy?.takeIf { it != id }?.let { "[$idLabel $id of $it]" } ?: "[$idLabel $id]"
}
