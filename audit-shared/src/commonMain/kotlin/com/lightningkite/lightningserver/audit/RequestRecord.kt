package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.database.HasId
import kotlinx.serialization.Serializable
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What a [DisclosureRecord.requestId] points at: who asked, from where, when, and how it went.
 *
 * Disclosure records repeat none of this — they carry a request id and nothing else about the
 * request — so without this table those references dangle. It is the reason the audit package owns a
 * request log at all rather than leaning on
 * [com.lightningkite.lightningserver.auth.AccessLogInterceptor], which writes log lines and is
 * deliberately fail-open: "the access log must never be the reason a request fails" is the opposite
 * of what a fail-closed log needs from the thing it references.
 *
 * @property _id The execution id itself. Using it as the primary key means no second column and no
 *   second index, and it makes a duplicate id a primary-key violation rather than a silent merge of
 *   two principals' activity under one identifier.
 * @property rootExecutionId The execution at the head of this row's causal chain, equal to [_id] when
 *   [parentRequestId] is null. Carried as well as the parent so that "everything that happened
 *   because of request X" is one indexed lookup rather than a recursive walk of parent pointers —
 *   which is the query this table exists to answer.
 * @property endpoint The matched route pattern rather than the literal target. The literal target
 *   carries record ids, which would duplicate — and spread — data the disclosure log already records
 *   precisely, and would give this column unbounded cardinality.
 * @property outcome Status code, or a WebSocket close reason. Null until the request completes.
 * @property durationMs Null until the request completes.
 * @property principal The resolved subject, or null when anonymous or unresolvable. [outcome]
 *   distinguishes the two.
 * @property engineRequestId The identifier the gateway or proxy in front of us minted for this
 *   request — API Gateway's `requestContext.requestId`, or a connection id for a socket. It is the
 *   join key back to that gateway's own access log, and it is trusted because the engine handed it
 *   to us rather than the caller. Deliberately a separate column from [upstreamRequestId]: one is a
 *   fact from our own infrastructure, the other is an unverified claim by whoever called us, and
 *   conflating them would let a caller forge a value that reads as infrastructure-supplied.
 * @property upstreamRequestId Whatever identifier the caller claimed, kept for diagnostics only.
 *   Never trusted, never used to correlate.
 */
@GenerateDataClassPaths
@Serializable
public data class RequestRecord(
    override val _id: Uuid,
    @Index val parentRequestId: Uuid? = null,
    @Index val rootExecutionId: Uuid,
    @Index val at: Instant,
    @Index val principal: String? = null,
    val sourceIp: String,
    val endpoint: String,
    val method: String,
    val outcome: String? = null,
    val durationMs: Long? = null,
    val engineRequestId: String? = null,
    val upstreamRequestId: String? = null,
) : HasId<Uuid> {
    public companion object
}
