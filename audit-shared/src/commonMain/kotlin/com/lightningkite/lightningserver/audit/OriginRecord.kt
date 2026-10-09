package com.lightningkite.lightningserver.audit

import com.lightningkite.services.data.GenerateDataClassPaths
import com.lightningkite.services.data.Index
import com.lightningkite.services.data.References
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
 */
@GenerateDataClassPaths
@Serializable
public data class OriginRecord<REQUEST_INFO>(
    override val _id: ID,
    @Index val parent: ExecutionId? = null, // may or may not be recorded in the table, the parent may not be an origin
    @Index val root: ExecutionId, // may or may not be recorded. Websocket phases are often their own root.
    val kind: ExecutionKind,
    val location: String,
    val request: REQUEST_INFO?
) : HasId<OriginRecord.ID> {
    @Serializable
    @JvmInline
    @References(OriginRecord::class)
    public value class ID(override val raw: ExecutionId) : TypedId<ExecutionId, ID>

    public val at: Instant
        get() = _id.raw.timestamp()

    @Serializable
    @GenerateDataClassPaths
    public data class StandardRequestInfo(
        val subjectId: Uuid?,
        val sessionId: Uuid?,
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
