package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.TypedId
import kotlin.jvm.JvmInline
import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * A record of an *originating* `Execution`. Here *originating* means that the execution's `origin == id`,
 * in other words, it is its own origin.
 *
 * Disclosures or other audit logs will reference their `origin`, which will point to one of these records.
 *
 * A [RequestRecord] comes from the following places:
 * - An `HttpRequest` being handled
 * - The `connect` phase of a WebSocket
 * - A `ScheduledTask`, `StartupTask`, or `PreDeployTask`. These three are server managed events and no user is
 *   responsible for them, but they can still expose information.
 */
@GenerateDataClassPaths
@Serializable
public data class RequestRecord(
    override val _id: ID,
    @Index val parentRequestId: ID? = null,
    @Index val rootRequestId: ID,
    @Index val principal: String? = null,
    val sourceIp: String,
    val endpoint: String,
    val method: String,
    val engineRequestId: String? = null,
    val upstreamRequestId: String? = null,
) : HasId<RequestRecord.ID> {
    @Serializable
    @JvmInline
    public value class ID(override val raw: ExecutionId) : TypedId<ExecutionId, ID>

    /** The instant this execution began, derived from the version-7 [_id]'s embedded timestamp. */
    public val at: Instant
        get() = _id.raw.timestamp()

    public companion object
}
