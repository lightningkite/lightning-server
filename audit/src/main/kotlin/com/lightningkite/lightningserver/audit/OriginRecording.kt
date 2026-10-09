package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.OverrideOnly
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpInterceptor
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.route
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.ExecutionInterceptor
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.websockets.*
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.UniqueViolationException
import com.lightningkite.services.database.insertOne
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Records the [OriginRecord]s other audit records reference lazily.
 *
 * Records are only written once [flush] is called (or [withCurrentOrigin], which in turn calls `flush`)
 *
 * Each execution captures its origin into its context, which child executions and tasks inherit, so a
 * touch anywhere down the chain writes every origin it descends from. An execution that never touches
 * writes nothing.
 */
public class OriginRecording<REQUEST_INFO>(
    private val table: Runtime<Table<OriginRecord<REQUEST_INFO>>>,
    public val requestInfoSerializer: KSerializer<REQUEST_INFO>,
    private val getInfo: suspend context(ServerRuntime) (Request<*>) -> REQUEST_INFO
) : ExecutionInterceptor, WebSocketInterceptor, HttpInterceptor {
    override val name: String = "OriginRecording"

    @Serializable
    private data class OriginQueue<REQUEST_INFO>(
        val queue: List<OriginRecord<REQUEST_INFO>> = emptyList(),
        val written: Set<OriginRecord.ID> = emptySet(),
    )

    private val contextKey = SerializableCache.Key<Execution, OriginQueue<REQUEST_INFO>>(
        "com.lightningkite.lightningserver.audit.OriginRecordInterceptor",
        OriginQueue.serializer(requestInfoSerializer),
    )

    @Serializable
    @ConsistentCopyVisibility
    private data class SocketOrigin<REQUEST_INFO> private constructor(
        val id: OriginRecord.ID,
        val queued: OriginRecord<REQUEST_INFO>?
    ) {
        constructor(queue: OriginRecord<REQUEST_INFO>) : this(queue._id, queue)

        fun markAsWritten(): SocketOrigin<REQUEST_INFO> = copy(queued = null)
    }

    // Claude: built once because the cache compares keys by equality, and a generic class's
    // serializer is a fresh, unequal instance on every call.
    private val socketOriginSerializer = SocketOrigin.serializer(requestInfoSerializer)

    // Claude: a socket's phases don't share an execution context, only the connect request, so the
    // origin waits there. Keyed per socket because a virtual sub-socket shares its carrier's cache.
    private fun socketKey(socketId: Execution.ID) = SerializableCache.Key<WebSocketConnectRequest<*>, SocketOrigin<REQUEST_INFO>>(
        "com.lightningkite.lightningserver.audit.OriginRecordInterceptor/${socketId.raw.raw}",
        socketOriginSerializer,
    )

    private val Execution.originId get() = OriginRecord.ID(origin.toExternal())

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
        var origins = context[contextKey] ?: OriginQueue()
        if (origins.queue.isNotEmpty()) {
            origins.queue.forEachConcurrently(maxConcurrent = 5) {
                try {
                    table().insertOne(it)
                } catch (_: UniqueViolationException) {
                    /*Squish. This means the row was already recorded, likely by a child execution, that's fine.*/
                }
            }
            origins = OriginQueue(written = origins.written + origins.queue.map { it._id })
            context[contextKey] = origins
        }
        check(runtime.execution.originId in origins.written) {
            "Execution ${runtime.execution.id} has no captured origin, so its audit records would reference nothing."
        }
    }

    context(runtime: ServerRuntime)
    internal suspend inline fun <T> withCurrentOrigin(withOrigin: (OriginRecord.ID) -> T): T {
        flush() // we're referencing the origin, flush to make sure it's in the table
        return withOrigin(runtime.execution.originId)
    }




    private fun ServerRuntime.queueOrigin(row: OriginRecord<REQUEST_INFO>) {
        val context = execution.context
        val origins = context[contextKey] ?: OriginQueue()
        if (row._id in origins.written || origins.queue.any { it._id == row._id }) return
        context[contextKey] = origins.copy(queue = origins.queue + row)
    }

    /** Gets the origin cached on the request, checks if it was already written previously, and checks if it was written during [action] */
    private inline fun <T> ServerRuntime.withSocketOrigin(
        request: WebSocketConnectRequest<*>,
        action: () -> T
    ): T {
        val context = this.execution.context

        val origin = request.cache[socketKey(request.socketId)]?.also { origin ->
            val current = context[contextKey] ?: OriginQueue()
            if (origin.id in current.written || current.queue.any { it._id == origin.id }) return@also

            context[contextKey] = when (val queued = origin.queued) {
                null -> current.copy(written = current.written + origin.id) // add the origin to written origins, we know it was written before in a previous phase
                else -> current.copy(queue = current.queue + queued)
            }
        }

        return if (origin?.queued == null) action() // nothing is queued for insertion, just run
        else try { action() } finally {
            if (context[contextKey]?.written?.contains(origin.id) == true) {
                // the origin was written during execution, we don't need to queue in the future
                request.cache[socketKey(request.socketId)] = origin.markAsWritten()
            }
        }
    }

    context(runtime: ServerRuntime)
    private suspend fun originOf(request: Request<*>?): OriginRecord<REQUEST_INFO> {
        check(runtime.execution.id == runtime.execution.origin) {
            "Attempted to get the origin of an execution which is not an origin: ${runtime.execution}"
        }
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
            _id = runtime.execution.originId,
            parent = execution.parent?.toExternal(),
            root = execution.rootExecution.toExternal(),
            kind = kind,
            location = location,
            request = request?.let { getInfo(it) }
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
                request.cache[socketKey(request.socketId)] = SocketOrigin(originOf(request))
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