package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.OverrideOnly
import com.lightningkite.lightningserver.audit.OriginRecord.RequestInfo
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
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
import com.lightningkite.lightningserver.websockets.WebSocketClose
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.lightningserver.websockets.WebSocketConnection
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import com.lightningkite.lightningserver.websockets.WebSocketInterceptor
import com.lightningkite.lightningserver.websockets.WebSocketSubscriptionMessage
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.UniqueViolationException
import com.lightningkite.services.database.insertOne
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Records the [OriginRecord]s other audit records reference, but only once [flush] asks for them.
 *
 * Each execution captures its origin into its context, which child executions and tasks inherit, so a
 * touch anywhere down the chain writes every origin it descends from. An execution that never touches
 * writes nothing.
 */
public class OriginRecordInterceptor(
    private val table: Runtime<Table<OriginRecord>>
) : ExecutionInterceptor, WebSocketInterceptor, HttpInterceptor {
    override val name: String = "OriginRecordInterceptor"

    @Serializable
    private data class OriginQueue(
        val queue: List<OriginRecord> = emptyList(),
        val written: Set<OriginRecord.ID> = emptySet(),
    )

    private val originsKey = SerializableCache.Key<Execution, OriginQueue>(
        "com.lightningkite.lightningserver.audit.OriginRecordInterceptor",
        OriginQueue.serializer(),
    )

    @Serializable
    @ConsistentCopyVisibility
    private data class SocketOrigin private constructor(
        val id: OriginRecord.ID,
        val queued: OriginRecord?
    ) {
        constructor(queue: OriginRecord) : this(queue._id, queue)

        fun markAsWritten(): SocketOrigin = copy(queued = null)
    }

    // Claude: a socket's phases don't share an execution context, only the connect request, so the
    // origin waits there. Keyed per socket because a virtual sub-socket shares its carrier's cache.
    private fun socketOriginKey(socketId: Execution.ID) = SerializableCache.Key<WebSocketConnectRequest<*>, SocketOrigin>(
        "com.lightningkite.lightningserver.audit.OriginRecordInterceptor/${socketId.raw.raw}",
        SocketOrigin.serializer(),
    )

    /**
     * Writes all queued origins for the current execution
     *
     * Call before writing any audit record that references the execution's origin.
     *
     * @throws IllegalStateException if the execution's origin was never captured.
     */
    context(runtime: ServerRuntime)
    internal suspend fun flush() {
        val context = runtime.execution.context
        var origins = context[originsKey] ?: OriginQueue()
        if (origins.queue.isNotEmpty()) {
            coroutineScope {
                for (row in origins.queue) launch {
                    try {
                        table().insertOne(row)
                    } catch (_: UniqueViolationException) {
                        /*Squish. This means the row was already recorded, that's fine.*/
                    }
                }
            }
            origins = OriginQueue(written = origins.written + origins.queue.map { it._id })
            context[originsKey] = origins
        }
        check(OriginRecord.ID(runtime.execution.origin.toExternal()) in origins.written) {
            "Execution ${runtime.execution.id} has no captured origin, so its audit records would reference nothing."
        }
    }

    private fun ServerRuntime.queueOrigin(row: OriginRecord) {
        val context = execution.context
        val origins = context[originsKey] ?: OriginQueue()
        if (row._id in origins.written || origins.queue.any { it._id == row._id }) return
        context[originsKey] = origins.copy(queue = origins.queue + row)
    }

    private inline fun <T> ServerRuntime.withSocketOrigin(
        request: WebSocketConnectRequest<*>,
        action: () -> T
    ): T {
        val context = this.execution.context

        val origin = request.cache[socketOriginKey(request.socketId)]?.also { origin ->
            val current = context[originsKey] ?: OriginQueue()
            if (origin.id in current.written || current.queue.any { it._id == origin.id }) return@also

            context[originsKey] = when (val queued = origin.queued) {
                null -> current.copy(written = current.written + origin.id) // add the origin to written origins, we know it was written before in a previous phase
                else -> current.copy(queue = current.queue + queued)
            }
        }

        return if (origin?.queued == null) action() // nothing is queued for insertion, just run
        else try { action() } finally {
            if (context[originsKey]?.written?.contains(origin.id) == true) {
                // the origin was written during execution, we don't need to queue in the future
                request.cache[socketOriginKey(request.socketId)] = origin.markAsWritten()
            }
        }
    }

    context(runtime: ServerRuntime)
    private suspend fun originOf(request: Request<*>?): OriginRecord {
        val execution = runtime.execution
        val (kind, location) = when (execution) {
            is Execution.Direct -> OriginRecord.ExecutionKind.Direct to ""
            is Execution.Http -> OriginRecord.ExecutionKind.Http to "${execution.endpoint.method} ${execution.endpoint.route()}"
            is Execution.WebSocket -> OriginRecord.ExecutionKind.WebSocket to execution.path.route()
            is Execution.PreDeploy -> OriginRecord.ExecutionKind.PreDeploy to execution.location.toString()
            is Execution.Schedule -> OriginRecord.ExecutionKind.Schedule to execution.location.toString()
            is Execution.Startup -> OriginRecord.ExecutionKind.Startup to execution.location.toString()
            is Execution.Task -> OriginRecord.ExecutionKind.Task to execution.location.toString()
        }
        return OriginRecord(
            _id = OriginRecord.ID(execution.id.toExternal()),
            parent = execution.parent?.toExternal(),
            root = execution.rootExecution.toExternal(),
            kind = kind,
            location = location,
            request = request?.let {
                val auth = try {
                    it[Authentication.CacheKey]?.let { it.fromMasquerade ?: it }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    runtime.logger.error(e) { "Could not determine auth of request for origin recording" }
                    null
                }
                RequestInfo(
                    principal = auth?.principalName,
                    subjectId = auth?.rawId,
                    sessionId = auth?.sessionId,
                    sourceIp = it.sourceIp,
                    engineRequestId = it.engineRequestId,
                    upstreamRequestId = it.upstreamRequestId,
                )
            },
        )
    }

    context(runtime: ServerRuntime)
    override suspend fun <T> intercept(cont: suspend context(ServerRuntime) () -> T): T {
        val execution = runtime.execution
        // Claude: requested executions are captured by the HTTP and WebSocket hooks, which can see the request.
        if (execution !is Execution.Requested && execution.id == execution.origin) runtime.queueOrigin(originOf(request = null))
        return cont()
    }

    context(runtime: ServerRuntime)
    override suspend fun <PATH : PathSpec> intercept(
        request: HttpRequest<PATH>, cont: suspend context(ServerRuntime) (HttpRequest<PATH>) -> HttpResponse
    ): HttpResponse {
        runtime.queueOrigin(originOf(request))
        return cont(request)
    }

    override fun <PATH : PathSpec, T> intercept(handler: WebSocketHandler<PATH, T>): WebSocketHandler<PATH, T> =
        object : DelegatingWebSocketHandler<PATH, T>(handler) {
            @OverrideOnly
            context(serverRuntime: ServerRuntime)
            override suspend fun willConnect(request: WebSocketConnectRequest<PATH>): T {
                request.cache[socketOriginKey(request.socketId)] = SocketOrigin(originOf(request))
                return serverRuntime.withSocketOrigin(request) {
                    wrapped.willConnect(request)
                }
            }

            @OverrideOnly
            context(serverRuntime: ServerRuntime)
            override suspend fun didConnect(connection: WebSocketConnection<PATH, T>) {
                return serverRuntime.withSocketOrigin(connection.request) {
                    wrapped.didConnect(connection)
                }
            }

            @OverrideOnly
            context(serverRuntime: ServerRuntime)
            override suspend fun messageFromClient(connection: WebSocketConnection<PATH, T>, frame: WebSocketFrame) {
                return serverRuntime.withSocketOrigin(connection.request) {
                    wrapped.messageFromClient(connection, frame)
                }
            }

            @OverrideOnly
            context(serverRuntime: ServerRuntime)
            override suspend fun messageFromSubscription(
                connection: WebSocketConnection<PATH, T>,
                topic: WebSocketSubscriptionMessage<*, *>,
            ) {
                return serverRuntime.withSocketOrigin(connection.request) {
                    wrapped.messageFromSubscription(connection, topic)
                }
            }

            @OverrideOnly
            context(serverRuntime: ServerRuntime)
            override suspend fun disconnect(connection: WebSocketConnection<PATH, T>, reason: WebSocketClose) {
                return serverRuntime.withSocketOrigin(connection.request) {
                    wrapped.disconnect(connection, reason)
                }
            }
        }

    context(builder: ServerBuilder)
    public fun install() {
        builder.install(this as HttpInterceptor)
        builder.install(this as WebSocketInterceptor)
        builder.install(this as ExecutionInterceptor)
    }
}