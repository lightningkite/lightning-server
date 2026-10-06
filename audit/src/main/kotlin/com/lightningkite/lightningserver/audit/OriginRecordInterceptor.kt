package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.OverrideOnly
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.http.HttpInterceptor
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.route
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.ExecutionInterceptor
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.websockets.DelegatingWebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketInterceptor
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.insertOne
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger("com.lightningkite.lightningserver.audit.RequestRecordInterceptor")
//
///**
// * Writes the [RequestRecord] that every [DisclosureRecord] of this request refers to.
// *
// * Logical scope, so each sub-request of a multiplexed request gets its own row — otherwise a bulk
// * request would record one row no matter how much was disclosed inside it.
// *
// * ## Two writes, and why they fail differently
// *
// * The row is written **before** the handler runs and updated **after** it finishes, because outcome
// * and duration are not known until the end while disclosures are written throughout. Writing first
// * guarantees the referent exists before anything points at it, and the same shape works for a
// * WebSocket, whose connection is recorded at connect and updated at close.
// *
// * The opening write is **fail-closed**: if it fails, the request fails, because nothing may be
// * disclosed under a request id that names no request.
// *
// * The closing update is **best-effort and logged**. By then the audit trail already answers who
// * received what; only outcome and duration are missing, and they are operational metadata rather
// * than disclosure facts. Failing a request whose disclosures were correctly recorded would destroy
// * more information than it protects.
// */
//public class RequestRecordInterceptor(
//    private val table: Runtime<Table<RequestRecord>>,
//) : HttpInterceptor, WebSocketInterceptor {
//    override val name: String = "RequestRecord"
//
//    context(runtime: ServerRuntime)
//    override suspend fun <PATH : PathSpec> intercept(
//        request: HttpRequest<PATH>,
//        cont: suspend context(ServerRuntime) (HttpRequest<PATH>) -> HttpResponse,
//    ): HttpResponse {
//        val started = TimeSource.Monotonic.markNow()
//        table().insert(listOf(request.opening(endpoint = request.path.route(), method = request.path.method.toString())))
//
//        var outcome = "failed"
//        try {
//            return cont(request).also { outcome = it.status.code.toString() }
//        } finally {
//            complete(runtime.execution.logicalId.uuid, outcome, started.elapsedNow().inWholeMilliseconds)
//        }
//    }
//
//    override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
//        object : DelegatingWebSocketHandler<PATH, T>(handler) {
//            @OverrideOnly
//            context(serverRuntime: ServerRuntime)
//            override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
//                table().insert(listOf(request.opening(endpoint = request.path.route(), method = "WEBSOCKET")))
//                return wrapped.willConnect(request)
//            }
//
//            @OverrideOnly
//            context(serverRuntime: ServerRuntime)
//            override suspend fun disconnect(connection: WebSocketConnection<PATH, T>, reason: WebSocketClose) {
//                try {
//                    wrapped.disconnect(connection, reason)
//                } finally {
//                    // Keyed by the socket, not this Disconnect phase, so it completes the row the Connect
//                    // phase opened. A socket's duration is its whole lifetime, which no monotonic mark
//                    // taken here could measure, so it is left to be derived from `at` and the close time.
//                    // The close code rather than the whole close, whose message and cause are not audit data.
//                    complete(serverRuntime.execution.logicalId.uuid, reason.code.code.toString(), durationMs = null)
//                }
//            }
//        }
//
//    context(runtime: ServerRuntime)
//    private suspend fun Request<*>.opening(endpoint: String, method: String) = RequestRecord(
//        _id = runtime.execution.logicalId.uuid,
//        // The parent's request row, not causedBy: when the parent is a WebSocket phase, causedBy names
//        // the phase, whose socket's row is keyed by the socket.
//        parentRequestId = runtime.history.dropLast(1).lastOrNull()?.origin?.uuid,
//        rootExecutionId = runtime.execution.rootExecution.uuid,
//        principal = principalOrNull(),
//        sourceIp = sourceIp,
//        endpoint = endpoint,
//        method = method,
//        engineRequestId = engineRequestId,
//        upstreamRequestId = upstreamRequestId,
//    )
//
//    context(runtime: ServerRuntime)
//    private suspend fun complete(requestId: Uuid, outcome: String, durationMs: Long?) {
//        try {
//            table().updateOneByIdIgnoringResult(requestId, modification(RequestRecord.path) {
//                it.outcome assign outcome
//                it.durationMs assign durationMs
//            })
//        } catch (e: CancellationException) {
//            throw e
//        } catch (e: Exception) {
//            logger.error(e) { "Could not complete the audit request record for $requestId" }
//        }
//    }
//}
//

public class OriginRecordInterceptor(
    private val table: Runtime<Table<OriginRecord>>
) : ExecutionInterceptor, WebSocketInterceptor, HttpInterceptor {
    override val name: String = "OriginRecordInterceptor"

    private fun Execution.isOrigin() = origin == id

    context(runtime: ServerRuntime)
    private suspend fun record(request: Request<*>?) {
        val execution = runtime.execution
        table().insertOne(
            OriginRecord(
                _id = OriginRecord.ID(execution.id.toExternal()),
                parent = execution.parent?.toExternal(),
                root = execution.rootExecution.toExternal(),
                kind = when (execution) {
                    is Execution.Direct -> OriginRecord.ExecutionKind.Direct
                    is Execution.Http -> OriginRecord.ExecutionKind.Http
                    is Execution.PreDeploy -> OriginRecord.ExecutionKind.PreDeploy
                    is Execution.WebSocket -> OriginRecord.ExecutionKind.WebSocket
                    is Execution.Schedule -> OriginRecord.ExecutionKind.Schedule
                    is Execution.Startup -> OriginRecord.ExecutionKind.Startup
                    is Execution.Task -> OriginRecord.ExecutionKind.Task
                },
                location = when (execution) {
                    is Execution.Direct -> ""
                    is Execution.Http -> "${execution.endpoint.method} ${execution.endpoint.route()}"
                    is Execution.WebSocket -> execution.path.route()
                    is Execution.PreDeploy -> execution.location.toString()
                    is Execution.Schedule -> execution.location.toString()
                    is Execution.Startup -> execution.location.toString()
                    is Execution.Task -> execution.location.toString()
                },
                request = request?.let {
                    val auth = try {
                        request[Authentication.CacheKey]?.let { it.fromMasquerade ?: it }
                    } catch (e: Exception) {
                        currentCoroutineContext().ensureActive()
                        runtime.logger.error(e) { "Could not determine auth of request for origin recording" }
                        null
                    }
                    OriginRecord.RequestInfo(
                        principal = auth?.principalName,
                        subjectId = auth?.rawId,
                        sessionId = auth?.sessionId,
                        sourceIp = request.sourceIp,
                        engineRequestId = request.engineRequestId,
                        upstreamRequestId = request.upstreamRequestId
                    )
                }
            )
        )
    }

    context(runtime: ServerRuntime)
    override suspend fun <T> intercept(cont: suspend context(ServerRuntime) () -> T): T {
        val execution = runtime.execution

        val result = cont()

        // we only need to record origins. Http and WebSockets are handled specifically below.
        if (execution.isOrigin() && execution !is Execution.Http && execution !is Execution.WebSocket) record(null)

        return result
    }

    override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
        object : DelegatingWebSocketHandler<PATH, T>(handler) {
            @OverrideOnly
            context(serverRuntime: ServerRuntime)
            override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                val result = wrapped.willConnect(request)
                record(request)
                return result
            }
        }

    context(runtime: ServerRuntime)
    override suspend fun <PATH : PathSpec> intercept(
        request: HttpRequest<PATH>, cont: suspend context(ServerRuntime) (HttpRequest<PATH>) -> HttpResponse
    ): HttpResponse {
        val result = cont(request)
        record(request)
        return result
    }
}
