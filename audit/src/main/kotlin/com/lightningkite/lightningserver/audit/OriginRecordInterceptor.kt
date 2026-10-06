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
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.eq
import com.lightningkite.services.database.modification
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.runtime.isRoot
import kotlinx.serialization.Serializable
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer

/**
 * Records the [OriginRecord] that other audit records reference, the first time one is needed.
 *
 * An execution that touches nothing audited writes no origin row.
 */
public class OriginRecordInterceptor(
    private val table: Runtime<Table<OriginRecord>>
) : ExecutionInterceptor, WebSocketInterceptor, HttpInterceptor {
    override val name: String = "OriginRecordInterceptor"

    /**
     * The origins an execution has yet to record, and the ones already recorded.
     *
     * Carried in the execution's context, so child executions and tasks inherit it.
     */
    @Serializable
    private data class AuditOrigins(
        val pending: List<OriginRecord> = emptyList(),
        val recorded: Set<ExecutionId> = emptySet(),
    ) {
        companion object : SerializableCache.Key<Execution, AuditOrigins> {
            override val id: String = "com.lightningkite.lightningserver.audit.OriginRecordInterceptor"
            override val serializer: KSerializer<AuditOrigins> = serializer()
        }
    }

    /**
     * Records every origin this execution descends from that is not yet recorded.
     *
     * Call before writing any audit record that references the execution's origin.
     *
     * @throws IllegalStateException if the execution's origin was never captured.
     */
    context(runtime: ServerRuntime)
    internal suspend fun ensureRecorded() {
        val execution = runtime.execution
        val origins = execution.context[AuditOrigins] ?: AuditOrigins()
        var recorded = origins.recorded
        if (origins.pending.isNotEmpty()) {
            for (row in origins.pending) {
                table().upsertOneIgnoringResult(
                    condition<OriginRecord> { it._id eq row._id },
                    modification<OriginRecord> { it.kind assign row.kind },
                    row,
                )
            }
            recorded = recorded + origins.pending.map { it._id.raw }
            execution.context[AuditOrigins] = AuditOrigins(pending = emptyList(), recorded = recorded)
        }
        check(execution.origin.toExternal() in recorded) {
            "Execution ${execution.id} has no captured origin, so its audit records would reference nothing."
        }
    }

    context(runtime: ServerRuntime)
    private suspend fun Execution.recordOrigin(request: Request<*>?) {
        val execution = runtime.execution
        val row = OriginRecord(
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
                // Resolved now rather than when the row is written: the request is not reachable from
                // a later point, such as a task. Resolution is cached, so the handler reuses it.
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
        val origins = context[AuditOrigins] ?: AuditOrigins()
        context[AuditOrigins] = origins.copy(pending = origins.pending + row)
    }

    context(runtime: ServerRuntime)
    override suspend fun <T> intercept(cont: suspend context(ServerRuntime) () -> T): T {
        val execution = runtime.execution

        when (execution) {
            is Execution.Http -> {}
            is Execution.WebSocket -> {
                if (execution.phase != Execution.WebSocket.Phase.Connect) {
                    val current = execution.context[AuditOrigins] ?: AuditOrigins()
                    // Above when we 'ensure recorded' it checks against the recorded on the context.
                    // A WebSocket's origin is recorded on connect, subsequent phases are not children and do not inherit connect's context
                    execution.context[AuditOrigins] = current.copy(recorded = current.recorded + execution.origin.toExternal())
                }
            }
            else -> if (execution.id == execution.origin) execution.recordOrigin(request = null)
        }

        return cont()
    }

    override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
        object : DelegatingWebSocketHandler<PATH, T>(handler) {
            @OverrideOnly
            context(serverRuntime: ServerRuntime)
            override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                // Recorded at once rather than lazily: later phases cannot see this phase's context.
                serverRuntime.execution.recordOrigin(request)
                ensureRecorded()
                return wrapped.willConnect(request)
            }
        }

    context(runtime: ServerRuntime)
    override suspend fun <PATH : PathSpec> intercept(
        request: HttpRequest<PATH>, cont: suspend context(ServerRuntime) (HttpRequest<PATH>) -> HttpResponse
    ): HttpResponse {
        runtime.execution.recordOrigin(request)
        return cont(request)
    }
}
