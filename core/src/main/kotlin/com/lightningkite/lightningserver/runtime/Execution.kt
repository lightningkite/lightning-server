package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.pathing.RawWebSocketPath
import com.lightningkite.services.data.UuidV7
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * What started one execution, and what caused it to start.
 *
 * An "execution" is one run of anything the server can run: an HTTP request, one WebSocket lifecycle
 * phase, a task, a schedule tick, a startup task, or a pre-deploy task. Every log the server writes
 * attributes itself to one of these, which is why the identifiers live here rather than on
 * [com.lightningkite.lightningserver.data.Request]: a task and a schedule tick have no request at
 * all, and threading correlation ids through request objects meant only requests could be correlated.
 *
 * ## Serializable, and deliberately small
 * Serialization is not a logging convenience: it is how [causedBy] crosses a queue. A task launched
 * from a request carries the launching execution's id in its queued payload, which is the only
 * mechanism that works on a serverless engine, where the launcher's process is gone by the time the
 * task runs. That means an initiator really is persisted — into task queues and into the DynamoDB
 * row backing a WebSocket. Everything here is bounded by URL length, and must stay that way: no
 * headers, no source IP, no principal, no body. Those stay on the request, which is already threaded
 * wherever they are needed. An initiator answers "what is running and why", not "what did the caller
 * send".
 *
 * ## Framework-set
 * Constructors are [InternalLightningServerApi] because an initiator that user code could mint would
 * let a caller's activity be filed under another execution's identity, which is the one thing the
 * audit trail must not permit. Read it freely; never build one.
 */
@Serializable
public sealed interface Execution {
    @Serializable
    @JvmInline
    public value class ID @InternalLightningServerApi constructor(public val raw: UuidV7) : Comparable<ID> {
        public companion object {
            @OptIn(InternalLightningServerApi::class)
            context(engine: Engine)
            public fun generate(): ID = ID(UuidV7.generateNonMonotonicAt(engine.clock.now()))
        }

        override fun compareTo(other: ID): Int = raw.compareTo(other.raw)

        public fun timestamp(): Instant = raw.timestamp()
    }

    /** Identifies this one execution. */
    public val id: ID
    public val causedBy: ID?
    public val rootExecution: ID

    public sealed interface Requested : Execution

    public sealed interface ServerManaged : Execution {
        override val rootExecution: ID get() = id
        override val causedBy: ID? get() = null
    }


    /**
     * One HTTP request, whether it arrived from a client or was dispatched inside a multiplexed one
     * such as `/meta/bulk`.
     *
     * [endpoint] is the concrete path the caller asked for, not the route pattern. The pattern is
     * derived from it on demand via `endpoint.match`, so nothing is stored twice; a record that wants
     * bounded cardinality (`RequestRecord.endpoint`) resolves the pattern itself.
     */
    @Serializable
    @SerialName("http")
    public data class Http @InternalLightningServerApi constructor(
        override val id: ID,
        override val causedBy: ID? = null,
        override val rootExecution: ID = id,
        val endpoint: RawHttpEndpoint<PathSpec>,
    ) : Execution, Requested

    /**
     * One phase of one WebSocket's life.
     *
     * Each phase is its own execution with its own [id], because on a serverless engine each
     * of the five lifecycle methods is a separate invocation — treating a socket as a single
     * execution would be factually wrong there. [socketId] is what stays constant across all phases
     * of one socket, and is the identity to attribute a socket's whole session to.
     */
    @Serializable
    @SerialName("ws")
    public data class WebSocket @InternalLightningServerApi constructor(
        override val id: ID,
        override val causedBy: ID? = null,
        override val rootExecution: ID = id,
        /** Constant for the socket's whole lifetime, across all phases. Same id as the 'id' of the first phase. */
        val socketId: ID,
        val path: RawWebSocketPath<PathSpec>,
        val phase: Phase,
    ) : Execution, Requested {
        public enum class Phase { Connect, Connected, ClientMessage, SubscriptionMessage, Disconnect }
    }

    /**
     * One run of a queued task.
     *
     * Tasks, schedules, startup and pre-deploy tasks are all registered under all-constant paths, so
     * [PathSegments] is their complete location.
     */
    @Serializable
    @SerialName("task")
    public data class Task @InternalLightningServerApi constructor(
        override val id: ID,
        override val causedBy: ID,
        override val rootExecution: ID,
        val location: PathSegments,
    ) : Execution

    /** One tick of a scheduled task. */
    @Serializable
    @SerialName("schedule")
    public data class Schedule @InternalLightningServerApi constructor(
        override val id: ID,
        val location: PathSegments,
    ) : Execution, ServerManaged

    /** One run of a startup task. */
    @Serializable
    @SerialName("startup")
    public data class Startup @InternalLightningServerApi constructor(
        override val id: ID,
        val location: PathSegments,
    ) : Execution, ServerManaged

    /** One run of a pre-deploy task. */
    @Serializable
    @SerialName("predeploy")
    public data class PreDeploy @InternalLightningServerApi constructor(
        override val id: ID,
        val location: PathSegments,
    ) : Execution, ServerManaged

    /**
     * An execution with no server-side origin: `TestRunner`, or a runtime built by hand.
     *
     * A deliberate hole in "every execution names what started it". Without it nothing outside the
     * server — a test, a script, an embedding application — could build a runtime at all, so the hole
     * is the price of the rule being enforceable everywhere else.
     */
    @Serializable
    @SerialName("direct")
    public data class Direct @InternalLightningServerApi constructor(
        // TODO: Require textual explanation?
        override val id: ID,
        override val causedBy: ID? = null,
        override val rootExecution: ID = id,
    ) : Execution
}

public fun Execution.isRoot(): Boolean = id == rootExecution

public val Execution.logicalId: Execution.ID get() = when (this) {
    is Execution.WebSocket -> socketId
    else -> id
}

/**
 * The initiator of a logical request dispatched inside this one, such as a `/meta/bulk` sub-request.
 *
 * A sub-request is a separate execution — it is independently attributable, and independently
 * audited — so it gets its own id rather than reusing the carrying request's, while staying joinable
 * to it through [Execution.causedBy] and to the whole batch through [Execution.rootExecution].
 */
@InternalLightningServerApi
context(engine: Engine)
public fun Execution.Http.subRequest(endpoint: RawHttpEndpoint<PathSpec>): Execution.Http = Execution.Http(
    id = Execution.ID.generate(),
    causedBy = id,
    rootExecution = rootExecution,
    endpoint = endpoint,
)

/**
 * The initiator of a later phase of the same socket.
 *
 * Derive from the socket's connect initiator — the one an engine persists with the connection — so
 * that every phase of a socket names the connect that opened it.
 */
@InternalLightningServerApi
context(engine: Engine)
public fun Execution.WebSocket.phase(phase: Execution.WebSocket.Phase): Execution.WebSocket = copy(
    id = Execution.ID.generate(),
    causedBy = id,
    rootExecution = rootExecution,
    phase = phase,
)

/**
 * The initiator of a logical sub-socket multiplexed inside this physical connection.
 *
 * A new [Execution.WebSocket.socketId], because a virtual socket has its own lifetime and its own
 * subscriptions; the physical connection carrying it stays reachable through [Execution.causedBy].
 */
@InternalLightningServerApi
context(engine: Engine)
public fun Execution.WebSocket.subConnection(path: RawWebSocketPath<PathSpec>): Execution.WebSocket {
    // One id for both, exactly as every engine mints a real connect. Minting two left a sub-socket's
    // own request-log row — which is keyed by socketId — unreachable from its execution id, so
    // nothing descending from it could find the row that names the person who opened it.
    val id = Execution.ID.generate()
    return Execution.WebSocket(
        id = id,
        causedBy = this@subConnection.id,
        rootExecution = rootExecution,
        socketId = id,
        path = path,
        phase = Execution.WebSocket.Phase.Connect,
    )
}

/**
 * The same socket and the same execution, at a rewritten path.
 *
 * For shims that only re-target where a socket was opened — the path arrives in a query parameter
 * rather than the URL, say. Rewriting is not opening: reusing the identity is what keeps the two
 * indistinguishable in the audit trail, which is correct here and wrong for [subConnection].
 */
@InternalLightningServerApi
public fun Execution.WebSocket.rewritePath(path: RawWebSocketPath<PathSpec>): Execution.WebSocket = copy(path = path)
