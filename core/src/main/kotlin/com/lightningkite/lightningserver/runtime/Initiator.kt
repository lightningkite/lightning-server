package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.pathing.RawWebSocketPath
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

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
public sealed interface Initiator {
    /** Identifies this one execution. */
    public val executionId: Uuid

    /** The execution that caused this one, or null if it started here. */
    public val causedBy: Uuid?

    /**
     * The execution at the head of this causal chain; equals [executionId] when [causedBy] is null.
     *
     * Carried alongside [causedBy] so that "everything that happened because of request X" is one
     * indexed lookup rather than a recursive walk of parent pointers — which is the query the audit
     * system exists to answer.
     *
     * **Not an attribution key.** The outermost execution is not necessarily the one that carried
     * the credentials — see [attributedTo].
     */
    public val rootExecutionId: Uuid

    /**
     * The execution whose request-log row names who is responsible for this one.
     *
     * Distinct from [rootExecutionId], and the distinction is load-bearing. The root answers "what
     * set this off"; this answers "who did it". For a plain request or socket they are the same
     * execution, which is why one field looked like enough — but the framework creates *inner*
     * executions that carry their own credentials, and there the two come apart:
     *
     * - a `/meta/bulk` sub-request takes per-sub-request query parameters, and `SessionManager.read`
     *   falls back to the `Authorization` and `jwt` query parameters
     * - a multiplexed sub-socket takes per-sub-socket query parameters the same way
     *
     * So an anonymous carrier can dispatch an authenticated inner execution. When that inner
     * execution launches a task, the task has no request row of its own, and [rootExecutionId] leads
     * to the *carrier's* row — which names nobody. The change becomes untraceable to the person who
     * made it. Both shapes are pinned by tests in the audit module.
     *
     * The rule is uniform: an execution that has a request-log row of its own attributes to that
     * row; one that does not — a task, a schedule tick — inherits the anchor of whatever launched
     * it. That is why this is carried rather than derived, and why it survives a queue.
     *
     * For an execution with no request row anywhere in its ancestry (startup, pre-deploy, a direct
     * runtime) this is its own [executionId] and resolves to nothing, exactly as [rootExecutionId]
     * does for the same executions.
     */
    public val attributedTo: Uuid

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
        override val executionId: Uuid,
        override val causedBy: Uuid? = null,
        override val rootExecutionId: Uuid = executionId,
        val endpoint: RawHttpEndpoint<PathSpec>,
    ) : Initiator {
        /** An HTTP execution has a request-log row of its own, keyed by its execution id. */
        override val attributedTo: Uuid get() = executionId
    }

    /**
     * One phase of one WebSocket's life.
     *
     * Each phase is its own execution with its own [executionId], because on a serverless engine each
     * of the five lifecycle methods is a separate invocation — treating a socket as a single
     * execution would be factually wrong there. [socketId] is what stays constant across all phases
     * of one socket, and is the identity to attribute a socket's whole session to.
     */
    @Serializable
    @SerialName("ws")
    public data class WebSocket @InternalLightningServerApi constructor(
        override val executionId: Uuid,
        override val causedBy: Uuid? = null,
        override val rootExecutionId: Uuid = executionId,
        /** Constant for the socket's whole lifetime, across all phases. */
        val socketId: Uuid,
        val path: RawWebSocketPath<PathSpec>,
        val phase: Phase,
    ) : Initiator {
        public enum class Phase { Connect, Connected, ClientMessage, SubscriptionMessage, Disconnect }

        /**
         * A socket's request-log row is keyed by [socketId] rather than by any one phase's execution
         * id, because connect and disconnect are separate invocations on a serverless engine. So the
         * socket, not the phase, is what a socket's work attributes to.
         */
        override val attributedTo: Uuid get() = socketId
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
        override val executionId: Uuid,
        override val causedBy: Uuid? = null,
        override val rootExecutionId: Uuid = executionId,
        /** A task has no request-log row of its own, so it inherits the anchor of whatever launched
         * it. Required, with no default: a task that invented its own anchor would attribute a
         * change to an execution that never authenticated anyone. */
        override val attributedTo: Uuid,
        val location: PathSegments,
    ) : Initiator

    /** One tick of a scheduled task. */
    @Serializable
    @SerialName("schedule")
    public data class Schedule @InternalLightningServerApi constructor(
        override val executionId: Uuid,
        override val causedBy: Uuid? = null,
        override val rootExecutionId: Uuid = executionId,
        /** A schedule tick has no request-log row, and normally no launching request either — its
         * anchor is then its own execution id and resolves to nothing, which is the honest answer. */
        override val attributedTo: Uuid,
        val location: PathSegments,
    ) : Initiator

    /** One run of a startup task. */
    @Serializable
    @SerialName("startup")
    public data class Startup @InternalLightningServerApi constructor(
        override val executionId: Uuid,
        override val causedBy: Uuid? = null,
        override val rootExecutionId: Uuid = executionId,
        val location: PathSegments,
    ) : Initiator {
        /** No request is involved at any point, so this anchors to itself and resolves to nothing. */
        override val attributedTo: Uuid get() = executionId
    }

    /** One run of a pre-deploy task. */
    @Serializable
    @SerialName("predeploy")
    public data class PreDeploy @InternalLightningServerApi constructor(
        override val executionId: Uuid,
        override val causedBy: Uuid? = null,
        override val rootExecutionId: Uuid = executionId,
        val location: PathSegments,
    ) : Initiator {
        /** No request is involved at any point, so this anchors to itself and resolves to nothing. */
        override val attributedTo: Uuid get() = executionId
    }

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
        override val executionId: Uuid,
        override val causedBy: Uuid? = null,
        override val rootExecutionId: Uuid = executionId,
    ) : Initiator {
        /** No request row exists, so this anchors to itself and resolves to nothing. */
        override val attributedTo: Uuid get() = executionId
    }
}

/**
 * The initiator of a logical request dispatched inside this one, such as a `/meta/bulk` sub-request.
 *
 * A sub-request is a separate execution — it is independently attributable, and independently
 * audited — so it gets its own id rather than reusing the carrying request's, while staying joinable
 * to it through [Initiator.causedBy] and to the whole batch through [Initiator.rootExecutionId].
 */
@InternalLightningServerApi
public fun Initiator.Http.subRequest(endpoint: RawHttpEndpoint<PathSpec>): Initiator.Http = Initiator.Http(
    executionId = Uuid.random(),
    causedBy = executionId,
    rootExecutionId = rootExecutionId,
    endpoint = endpoint,
)

/**
 * The initiator of a later phase of the same socket.
 *
 * Derive from the socket's connect initiator — the one an engine persists with the connection — so
 * that every phase of a socket names the connect that opened it.
 */
@InternalLightningServerApi
public fun Initiator.WebSocket.phase(phase: Initiator.WebSocket.Phase): Initiator.WebSocket = copy(
    executionId = Uuid.random(),
    causedBy = executionId,
    rootExecutionId = rootExecutionId,
    phase = phase,
)

/**
 * The initiator of a logical sub-socket multiplexed inside this physical connection.
 *
 * A new [Initiator.WebSocket.socketId], because a virtual socket has its own lifetime and its own
 * subscriptions; the physical connection carrying it stays reachable through [Initiator.causedBy].
 */
@InternalLightningServerApi
public fun Initiator.WebSocket.subConnection(path: RawWebSocketPath<PathSpec>): Initiator.WebSocket {
    // One id for both, exactly as every engine mints a real connect. Minting two left a sub-socket's
    // own request-log row — which is keyed by socketId — unreachable from its execution id, so
    // nothing descending from it could find the row that names the person who opened it.
    val id = Uuid.random()
    return Initiator.WebSocket(
        executionId = id,
        causedBy = executionId,
        rootExecutionId = rootExecutionId,
        socketId = id,
        path = path,
        phase = Initiator.WebSocket.Phase.Connect,
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
public fun Initiator.WebSocket.rewritePath(path: RawWebSocketPath<PathSpec>): Initiator.WebSocket = copy(path = path)
