package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.http.HttpLogicalInterceptor
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.Initiator
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.websockets.DelegatingWebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketConnection
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketLogicalInterceptor
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.modification
import com.lightningkite.services.database.updateOneByIdIgnoringResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger("com.lightningkite.lightningserver.audit.RequestRecordInterceptor")

/**
 * Writes the [RequestRecord] that every [DisclosureRecord] of this request refers to.
 *
 * Logical scope, so each sub-request of a multiplexed request gets its own row — otherwise a bulk
 * request would record one row no matter how much was disclosed inside it.
 *
 * ## Two writes, and why they fail differently
 *
 * The row is written **before** the handler runs and updated **after** it finishes, because outcome
 * and duration are not known until the end while disclosures are written throughout. Writing first
 * guarantees the referent exists before anything points at it, and the same shape works for a
 * WebSocket, whose connection is recorded at connect and updated at close.
 *
 * The opening write is **fail-closed**: if it fails, the request fails, because nothing may be
 * disclosed under a request id that names no request.
 *
 * The closing update is **best-effort and logged**. By then the audit trail already answers who
 * received what; only outcome and duration are missing, and they are operational metadata rather
 * than disclosure facts. Failing a request whose disclosures were correctly recorded would destroy
 * more information than it protects.
 */
public class RequestRecordInterceptor(
    private val table: Runtime<Table<RequestRecord>>,
) : HttpLogicalInterceptor, WebSocketLogicalInterceptor {
    override val name: String = "RequestRecord"

    context(runtime: ServerRuntime)
    override suspend fun intercept(
        request: HttpRequest<*>,
        cont: suspend context(ServerRuntime) (HttpRequest<*>) -> HttpResponse,
    ): HttpResponse {
        val started = TimeSource.Monotonic.markNow()
        table().insert(listOf(request.opening(endpoint = request.route(), method = request.path.method.toString())))

        var outcome = "failed"
        try {
            return cont(request).also { outcome = it.status.code.toString() }
        } finally {
            complete(runtime.initiator.requestRecordId, outcome, started.elapsedNow().inWholeMilliseconds)
        }
    }

    override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
        object : DelegatingWebSocketHandler<PATH, T>(handler) {
            context(serverRuntime: ServerRuntime)
            override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                table().insert(listOf(request.opening(endpoint = request.route(), method = "WEBSOCKET")))
                return wrapped.willConnect(request)
            }

            context(serverRuntime: ServerRuntime)
            override suspend fun disconnect(connection: WebSocketConnection<PATH, T>, reason: WebSocketClose) {
                try {
                    wrapped.disconnect(connection, reason)
                } finally {
                    // A socket's duration is its whole lifetime, which no monotonic mark taken here
                    // could measure, so it is left to be derived from `at` and the close time.
                    complete(serverRuntime.initiator.requestRecordId, reason.toString(), durationMs = null)
                }
            }
        }

    context(runtime: ServerRuntime)
    private suspend fun Request<*>.opening(endpoint: String, method: String) = RequestRecord(
        _id = runtime.initiator.requestRecordId,
        parentRequestId = runtime.initiator.causedBy,
        rootExecutionId = runtime.initiator.rootExecutionId,
        principal = principalOrNull(),
        sourceIp = sourceIp,
        endpoint = endpoint,
        method = method,
        engineRequestId = engineRequestId,
        upstreamRequestId = upstreamRequestId,
    )

    context(runtime: ServerRuntime)
    private suspend fun complete(requestId: Uuid, outcome: String, durationMs: Long?) {
        try {
            table().updateOneByIdIgnoringResult(requestId, modification(RequestRecord.path) {
                it.outcome assign outcome
                it.durationMs assign durationMs
            })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Could not complete the audit request record for $requestId" }
        }
    }
}

/**
 * The matched route pattern, falling back to the literal target when nothing matched.
 *
 * Matching needs the server definition, hence the context; the same resolution telemetry uses.
 */
context(runtime: ServerRuntime)
private fun HttpRequest<*>.route(): String = try {
    path.match.path.pathSpec.toString()
} catch (_: Exception) {
    "/" + path.pathSegments.toString()
}

context(runtime: ServerRuntime)
private fun WebSocketConnectRequest<*>.route(): String = try {
    path.match.path.pathSpec.toString()
} catch (_: Exception) {
    "/" + path.pathSegments.toString()
}

/**
 * The resolved subject, or null when the request is anonymous or its credentials could not be
 * resolved at all.
 *
 * Resolved here, at the start of the request, rather than at the end: a request that dies mid-flight
 * should still be attributable. Resolution is memoized, so the handler's own auth check reuses it.
 */
context(runtime: ServerRuntime)
private suspend fun Request<*>.principalOrNull(): String? = try {
    this[Authentication.CacheKey]?.toString()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/**
 * The id the request log row for this execution is keyed by.
 *
 * For a socket that is the socket id rather than the phase's own execution id: the row is opened at
 * connect and completed at disconnect, which are separate executions on a serverless engine, and a
 * row keyed by either one of them could not be found by the other. It is also what every disclosure
 * on that socket points at, so the two must agree — hence one definition rather than two.
 *
 * ## The attribution this gives up
 * A socket's message phases are executions in their own right and can disclose. Keying by the socket
 * means their disclosures attribute to the socket, so the audit answer for a long-lived connection is
 * "sometime during this session" rather than "in response to this message". That is exactly what the
 * server did before the initiator existed — a socket's correlation id was deliberately constant for
 * its whole lifetime — so this is non-regressing rather than a new gap.
 *
 * Closing it would mean a row per phase execution, which for a chatty socket is a row per client
 * message. That is an audit-design decision with a real cost, and it belongs with the rest of the
 * audit work in `plans/audit-logging.md` (§5.8), not with the refactor that made it visible.
 */
internal val Initiator.requestRecordId: Uuid
    get() = (this as? Initiator.WebSocket)?.socketId ?: executionId
