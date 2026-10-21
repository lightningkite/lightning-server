package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.http.HttpHeader
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.generateFromServerClock
import com.lightningkite.lightningserver.sessions.AuthEvent
import com.lightningkite.lightningserver.sessions.AuthEventListener
import com.lightningkite.services.data.Unsafe
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.insertOne

/**
 * Writes [AuthEvent]s to the audit database.
 *
 * Nothing is recorded until it is installed with `install` in a [ServerBuilder][com.lightningkite.lightningserver.definition.builder.ServerBuilder].
 * Install it at most once, or every event is recorded twice.
 *
 * [AuthEvent.TokenRejected] and [AuthEvent.ProofRejected] are not recorded. Both are raised before any
 * real session or account is involved, so anyone can produce them at will, and recording them would
 * let an unauthenticated caller flood the audit database. They write no [OriginRecord] either, so such
 * attempts only show up in the server's access log.
 */
public class AuthEventLog(
    private val origins: OriginRecording<*>,
    public val table: Runtime<Table<AuthEventRecord>>,
) : AuthEventListener {
    override val name: String = "AuthEventLog"

    context(runtime: ServerRuntime)
    override suspend fun onEvent(event: AuthEvent) {
        // SAFETY: origins.flush() runs below, before the record is inserted. It cannot run first, because some events are not recorded.
        @OptIn(Unsafe::class)
        fun recordOf(type: AuthEventRecord.Type, principal: String) = AuthEventRecord(
            _id = AuthEventRecord.ID(UuidV7.generateFromServerClock()),
            origin = origins.currentOrigin(),
            type = type,
            principal = principal,
            sourceIp = event.request?.sourceIp,
            userAgent = event.request?.headers?.get(HttpHeader.UserAgent)?.root,
        )

        val record = when (event) {
            is AuthEvent.TokenRejected, is AuthEvent.ProofRejected -> return
            is AuthEvent.SessionRejected -> recordOf(AuthEventRecord.Type.AuthenticationFailed, event.principal).copy(
                sessionId = event.sessionId,
                failureReason = event.reason.name,
            )
            is AuthEvent.SessionCreated -> recordOf(AuthEventRecord.Type.SessionCreated, event.principal).copy(
                sessionId = event.sessionId,
            )
            is AuthEvent.SessionTerminated -> recordOf(AuthEventRecord.Type.SessionTerminated, event.principal).copy(
                sessionId = event.sessionId,
            )
            is AuthEvent.ProofCredentialRejected -> recordOf(AuthEventRecord.Type.ProofRejected, event.principal).copy(
                failureReason = event.reason.name,
                method = event.method.via,
                methodProperty = event.method.property,
            )
            is AuthEvent.ProofAccepted -> recordOf(AuthEventRecord.Type.ProofAccepted, event.principal).copy(
                method = event.method.via,
                methodProperty = event.method.property,
            )
            is AuthEvent.ProofIssued -> recordOf(AuthEventRecord.Type.ProofIssued, event.principal).copy(
                method = event.method.via,
                methodProperty = event.method.property,
            )
        }
        origins.flush()
        table().insertOne(record)
    }
}
