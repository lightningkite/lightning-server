package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.InternalLightningServerApi
import com.lightningkite.lightningserver.definition.Extendable
import com.lightningkite.lightningserver.definition.MutableExtensions
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
        val endpoint: RawHttpEndpoint<*>,
//        override val extensions: MutableExtensions = MutableExtensions()
    ) : Execution, Requested {
        @InternalLightningServerApi public constructor(
            id: ID,
            parent: Execution?,
            endpoint: RawHttpEndpoint<*>
        ) : this(id, causedBy = parent?.id, rootExecution = parent?.rootExecution ?: id, endpoint)
    }

    /**
     * One phase of one WebSocket's life.
     *
     * Each phase is its own execution with its own [id], because on a serverless engine each
     * of the five lifecycle methods is a separate invocation — treating a socket as a single
     * execution would be factually wrong there. [socketId] is what stays constant across all phases
     * of one socket, and is the identity to attribute a socket's whole session to.
     */
    @Serializable
    @SerialName("websocket")
    public data class WebSocket @InternalLightningServerApi constructor(
        override val id: ID,
        override val causedBy: ID? = null,
        override val rootExecution: ID = id,
        /** Constant for the socket's whole lifetime, across all phases. Same id as the 'id' of the first phase. */
        val socketId: ID,
        val path: RawWebSocketPath<*>,
        val phase: Phase,
//        override val extensions: MutableExtensions = MutableExtensions()
    ) : Execution, Requested {
        @InternalLightningServerApi public constructor(
            id: ID,
            parent: Execution?,
            socketId: ID,
            path: RawWebSocketPath<*>,
            phase: Phase
        ) : this(id, causedBy = parent?.id, rootExecution = parent?.rootExecution ?: id, socketId, path, phase)

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
//        override val extensions: MutableExtensions = MutableExtensions()
    ) : Execution {
        @InternalLightningServerApi public constructor(
            id: ID,
            parent: Execution,
            location: PathSegments
        ) : this(id, causedBy = parent.id, rootExecution = parent.rootExecution, location)
    }

    /** One tick of a scheduled task. */
    @Serializable
    @SerialName("schedule")
    public data class Schedule @InternalLightningServerApi constructor(
        override val id: ID,
        val location: PathSegments,
//        override val extensions: MutableExtensions = MutableExtensions()
    ) : Execution, ServerManaged

    /** One run of a startup task. */
    @Serializable
    @SerialName("startup")
    public data class Startup @InternalLightningServerApi constructor(
        override val id: ID,
        val location: PathSegments,
//        override val extensions: MutableExtensions = MutableExtensions()
    ) : Execution, ServerManaged

    /** One run of a pre-deploy task. */
    @Serializable
    @SerialName("predeploy")
    public data class PreDeploy @InternalLightningServerApi constructor(
        override val id: ID,
        val location: PathSegments,
//        override val extensions: MutableExtensions = MutableExtensions()
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
//        override val extensions: MutableExtensions = MutableExtensions()
    ) : Execution
}

/**
 * The identity of the logical unit of work this execution belongs to: the socket for a
 * [Execution.WebSocket], whose five phases are five executions of one connection, and the execution
 * itself for everything else.
 */
public val Execution.logicalId: Execution.ID get() = when (this) {
    is Execution.WebSocket -> socketId
    else -> id
}

/**
 * Whether this execution is the outermost one — nothing the server is running carried it.
 *
 * True for a request a client sent, a socket a client opened (in any of its phases, so long as that
 * phase was not itself dispatched from within another running execution), a schedule tick, a startup
 * or pre-deploy task. False for anything dispatched inside something else: a `/meta/bulk` sub-request,
 * a virtual socket multiplexed inside a real one, a task launched by a request.
 *
 * Equivalent to `rootExecution == id`, since a [causedBy] of `null` is exactly the case where nothing
 * caused this execution to inherit another's root. [causedBy] is the more direct read.
 *
 * Interceptors use this when their concern belongs to the connection rather than to the work, since
 * the interceptor chains run for every logical request and socket.
 */
public fun Execution.isRoot(): Boolean = causedBy == null
