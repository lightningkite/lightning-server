package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.http.HttpLogicalInterceptor
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketConnection
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketLogicalInterceptor
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.modification
import com.lightningkite.services.database.updateOneByIdIgnoringResult
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlin.time.TimeSource

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
            complete(request.requestId, outcome, started.elapsedNow().inWholeMilliseconds)
        }
    }

    override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
        object : WebSocketHandler<PATH, T> {
            override val storageSerializer: KSerializer<T> get() = handler.storageSerializer

            context(serverRuntime: ServerRuntime)
            override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                table().insert(listOf(request.opening(endpoint = request.route(), method = "WEBSOCKET")))
                return handler.willConnect(request)
            }

            context(connection: WebSocketConnection<PATH, T>)
            override suspend fun didConnect(): Unit = handler.didConnect()

            context(connection: WebSocketConnection<PATH, T>)
            override suspend fun messageFromClient(frame: WebSocketFrame): Unit = handler.messageFromClient(frame)

            context(connection: WebSocketConnection<PATH, T>)
            override suspend fun messageFromSubscription(topic: WebSocketSubscriptionMessage<*, *>): Unit =
                handler.messageFromSubscription(topic)

            context(connection: WebSocketConnection<PATH, T>)
            override suspend fun disconnect(reason: WebSocketClose) {
                try {
                    handler.disconnect(reason)
                } finally {
                    // A socket's duration is its whole lifetime, which no monotonic mark taken here
                    // could measure, so it is left to be derived from `at` and the close time.
                    with(connection as ServerRuntime) {
                        complete(connection.request.requestId, reason.toString(), durationMs = null)
                    }
                }
            }
        }

    context(runtime: ServerRuntime)
    private suspend fun Request<*>.opening(endpoint: String, method: String) = RequestRecord(
        _id = requestId,
        parentRequestId = parentRequestId,
        at = now(),
        principal = principalOrNull(),
        sourceIp = sourceIp,
        endpoint = endpoint,
        method = method,
        upstreamRequestId = upstreamRequestId,
    )

    context(runtime: ServerRuntime)
    private suspend fun complete(requestId: String, outcome: String, durationMs: Long?) {
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
