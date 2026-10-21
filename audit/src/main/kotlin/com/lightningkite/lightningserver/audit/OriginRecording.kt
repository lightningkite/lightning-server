package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.concurrent.forEachConcurrent
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.SerializableCache
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.pathing.route
import com.lightningkite.lightningserver.runtime.Execution
import com.lightningkite.lightningserver.runtime.ExecutionInterceptor
import com.lightningkite.lightningserver.runtime.Invocation
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.lightningserver.websockets.WebSocketConnectRequest
import com.lightningkite.services.data.Unsafe
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.UniqueViolationException
import com.lightningkite.services.database.get
import com.lightningkite.services.database.insertOne
import com.lightningkite.services.database.serializerOrContextual
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Records the [OriginRecord]s other audit records reference lazily.
 *
 * Records are only written once [flush] is called (or [withCurrentOrigin], which in turn calls `flush`)
 *
 * Each execution captures its origin into its context, which child executions and tasks inherit, so a
 * touch anywhere down the chain writes every origin it descends from. An execution that never touches
 * writes nothing.
 *
 * [REQUEST_INFO] must compare by value, such as a data class: an origin id that is already recorded is
 * accepted only if the recorded row equals the one being written.
 */
public class OriginRecording<REQUEST_INFO>(
    public val table: Runtime<Table<OriginRecord<REQUEST_INFO>>>,
    public val info: RequestInfo<REQUEST_INFO>
) : ExecutionInterceptor {
    public data class RequestInfo<T>(
        val serializer: KSerializer<T>,
        val getInfo: suspend context(ServerRuntime) (Request<*>) -> T
    ) {
        public companion object {
            public val Standard: RequestInfo<OriginRecord.StandardRequestInfo> = RequestInfo(
                OriginRecord.StandardRequestInfo.serializer(),
            ) { request ->
                val auth = try {
                    request[Authentication.CacheKey]?.let { it.fromMasquerade ?: it }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    serverRuntime.logger.error(e) { "Could not determine auth of request for origin recording" }
                    null
                }
                OriginRecord.StandardRequestInfo(
                    subjectId = auth?.rawId?.let(Uuid::parseOrNull),
                    sessionId = auth?.sessionId?.let(Uuid::parseOrNull),
                    sourceIp = request.sourceIp,
                    engineRequestId = request.engineRequestId,
                    upstreamRequestId = request.upstreamRequestId,
                )
            }
        }
    }

    override val name: String = "OriginRecording"

    /** @property current The origin of the execution holding this queue, inherited by everything it starts. */
    @Serializable
    private data class OriginQueue<REQUEST_INFO>(
        val queue: List<OriginRecord<REQUEST_INFO>> = emptyList(),
        val written: Set<OriginRecord.ID> = emptySet(),
        val current: OriginRecord.ID? = null,
    )

    private val contextKey = SerializableCache.Key<Execution, OriginQueue<REQUEST_INFO>>(
        "com.lightningkite.lightningserver.audit.OriginRecording",
        OriginQueue.serializer(info.serializer),
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
    private val socketOriginSerializer = SocketOrigin.serializer(info.serializer)

    // Claude: a socket's phases don't share an execution context, only the connect request, so the
    // origin waits there. Keyed per socket because a virtual sub-socket shares its carrier's cache.
    private fun socketKey(socketId: Execution.ID) = SerializableCache.Key<WebSocketConnectRequest<*>, SocketOrigin<REQUEST_INFO>>(
        "com.lightningkite.lightningserver.audit.OriginRecording/${socketId.raw.raw}",
        socketOriginSerializer,
    )

    @OptIn(Unsafe::class) // SAFETY: This is where origins are minted, ids are created for records queued for insertion
    private val Execution.originId get() = OriginRecord.ID(origin.toExternal())

    /**
     * Writes all queued origins for the current execution
     *
     * Call before writing any audit record that references the execution's origin.
     *
     * @throws IllegalStateException if the execution's origin was never captured, or if an origin with the
     *   same id but different contents was already recorded.
     */
    context(runtime: ServerRuntime)
    internal suspend fun flush() {
        val context = runtime.execution.context
        var origins = context[contextKey] ?: OriginQueue()
        if (origins.queue.isNotEmpty()) {
            origins.queue.forEachConcurrent(concurrent = 5) {
                try {
                    table().insertOne(it)
                } catch (e: UniqueViolationException) {
                    // Claude: a child execution re-writing its parent's origin writes an identical row. A different
                    // row means two requests share an id, as a misbehaving trusted request-id header can cause.
                    if (table().get(it._id) != it) throw IllegalStateException(
                        "Origin ${it._id} is already recorded with different contents, so two executions share one id. " +
                            "Their audit records would be attributed to the same origin. Check the trusted request-id header.",
                        e,
                    )
                }
            }
            origins = origins.copy(queue = emptyList(), written = origins.written + origins.queue.map { it._id })
            context[contextKey] = origins
        }
        check(runtime.execution.originId in origins.written) {
            "Execution ${runtime.execution.id} has no captured origin, so its audit records would reference nothing."
        }
    }

    @Unsafe("flush() must be called before anything using this id is recorded to the database.")
    context(runtime: ServerRuntime)
    internal fun currentOrigin(): OriginRecord.ID = runtime.execution.originId

    /**
     * [Flushes][flush] any queued origins on the execution, ensuring they are recorded to the
     * database, then calls [withOrigin] with the id of the closest origin to this execution.
     * */
    context(runtime: ServerRuntime)
    internal suspend inline fun <T> withCurrentOrigin(withOrigin: (OriginRecord.ID) -> T): T {
        flush() // we're referencing the origin, flush to make sure it's in the table
        return withOrigin(runtime.execution.originId)
    }


    private fun ServerRuntime.queueOrigin(row: OriginRecord<REQUEST_INFO>) {
        val context = execution.context
        val origins = context[contextKey] ?: OriginQueue()
        if (row._id in origins.written || origins.queue.any { it._id == row._id }) {
            context[contextKey] = origins.copy(current = row._id)
            return
        }
        context[contextKey] = origins.copy(queue = origins.queue + row, current = row._id)
    }

    /** Gets the origin cached on the request, checks if it was already written previously, and checks if it was written during [action] */
    private suspend fun <T> ServerRuntime.withSocketOrigin(
        request: WebSocketConnectRequest<*>,
        action: suspend () -> T
    ): T {
        val context = this.execution.context

        val origin = request.cache[socketKey(request.socketId)]?.also { origin ->
            val current = (context[contextKey] ?: OriginQueue()).copy(current = origin.id)
            context[contextKey] = when {
                origin.id in current.written || current.queue.any { it._id == origin.id } -> current
                origin.queued == null -> current.copy(written = current.written + origin.id) // add the origin to written origins, we know it was written before in a previous phase
                else -> current.copy(queue = current.queue + origin.queued)
            }
        }

        return if (origin?.queued == null) action() // nothing is queued for insertion, just run
        else try {
            action()
        } finally {
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
            // Claude: the parent's origin rather than the parent execution, which for a socket phase has no row.
            parent = execution.context[contextKey]?.current?.raw ?: execution.parent?.toExternal(),
            root = execution.rootExecution.toExternal(),
            kind = kind,
            location = location,
            request = request?.let { info.getInfo(it) }
        )
    }

    context(runtime: ServerRuntime)
    override suspend fun <T> intercept(invocation: Invocation, cont: suspend context(ServerRuntime) () -> T): T =
        when (invocation) {
            is Invocation.Http -> {
                runtime.queueOrigin(originOf(invocation.request))
                cont()
            }

            is Invocation.WebSocket -> {
                val request = invocation.request
                if (invocation.execution.phase == Execution.WebSocket.Phase.Connect) {
                    request.cache[socketKey(request.socketId)] = SocketOrigin(originOf(request))
                }
                runtime.withSocketOrigin(request) { cont() }
            }

            is Invocation.Unrequested -> {
                val execution = invocation.execution
                if (execution.id == execution.origin) runtime.queueOrigin(originOf(request = null))
                cont()
            }
        }
}
