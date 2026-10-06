package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.TypedId
import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * A record of an *originating* `Execution`. Here *originating* means that the execution's `origin == id`,
 * in other words, it is its own origin.
 *
 * Disclosures or other audit logs will reference their `origin`, which will point to one of these records.
 *
 * A [OriginRecord] comes from the following places:
 * - An `HttpRequest` being handled
 * - The `connect` phase of a WebSocket
 * - A `ScheduledTask`, `StartupTask`, or `PreDeployTask`. These three are server managed events and no user is
 *   responsible for them, but they can still expose information.
 *
 */
// * @property _id The execution id itself. Using it as the primary key means no second column and no
// *   second index, and it makes a duplicate id a primary-key violation rather than a silent merge of
// *   two principals' activity under one identifier.
// * @property rootExecutionId The execution at the head of this row's causal chain, equal to [_id] when
// *   [parent] is null. Carried as well as the parent so that "everything that happened
// *   because of request X" is one indexed lookup rather than a recursive walk of parent pointers —
// *   which is the query this table exists to answer.
// * @property endpoint The matched route pattern rather than the literal target. The literal target
// *   carries record ids, which would duplicate — and spread — data the disclosure log already records
// *   precisely, and would give this column unbounded cardinality.
// * @property principal The resolved subject, or null when anonymous or unresolvable. [outcome]
// *   distinguishes the two.
// * @property engineRequestId The identifier the gateway or proxy in front of us minted for this
// *   request — API Gateway's `requestContext.requestId`, or a connection id for a socket. It is the
// *   join key back to that gateway's own access log, and it is trusted because the engine handed it
// *   to us rather than the caller. Deliberately a separate column from [upstreamRequestId]: one is a
// *   fact from our own infrastructure, the other is an unverified claim by whoever called us, and
// *   conflating them would let a caller forge a value that reads as infrastructure-supplied.
// * @property upstreamRequestId Whatever identifier the caller claimed, kept for diagnostics only.
// *   Never trusted, never used to correlate.
@GenerateDataClassPaths
@Serializable
public data class OriginRecord(
    override val _id: ID,
    @Index val parent: ExecutionId? = null, // may or may not be recorded in the table, the parent may not be an origin
    @Index val root: ExecutionId, // may or may not be recorded. Websocket phases are often their own root.
    val kind: ExecutionKind,
    val location: String,
    val request: RequestInfo?,
) : HasId<OriginRecord.ID> {
    @Serializable
    @JvmInline
    public value class ID(override val raw: ExecutionId) : TypedId<ExecutionId, ID>

    public val at: Instant
        get() = _id.raw.timestamp()

    @Serializable
    @GenerateDataClassPaths
    public data class RequestInfo(
        val principal: String?,
        val subjectId: String?,
        val sessionId: String?,
        val sourceIp: String,
        val engineRequestId: String?,
        val upstreamRequestId: String?,
    )

    @Serializable
    public enum class ExecutionKind {
        Http,
        WebSocket,
        Task,
        Schedule,
        Startup,
        PreDeploy,
        Direct
    }
}
