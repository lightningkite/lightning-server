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
 * Writes [AuthEvent]s to the audit database. Installed by [AuthEventLog].
 *
 * [AuthEvent.TokenRejected] and [AuthEvent.ProofRejected] are not recorded. Both are raised before any
 * real session or account is involved, so anyone can produce them at will, and recording them would
 * let an unauthenticated caller flood the audit database. The request log still records each attempt.
 */
public class AuthEventLogListener(
    private val origins: OriginRecording<*>,
    private val table: Runtime<Table<AuthEventRecord>>,
) : AuthEventListener {
    override val name: String = "AuthEventLog"

    context(runtime: ServerRuntime)
    override suspend fun onEvent(event: AuthEvent) {
        @OptIn(Unsafe::class) // SAFETY: origins.flush() is called below once we know we are actually going to record the event. Some events are not recorded, so it needs to be done out of order.
        val base = AuthEventRecord(
            _id = AuthEventRecord.ID(UuidV7.generateFromServerClock()),
            origin = origins.currentOrigin(),
            type = AuthEventRecord.Type.AuthenticationFailed,
            sourceIp = event.request?.sourceIp,
            userAgent = event.request?.headers?.get(HttpHeader.UserAgent)?.root,
        )
        val record = when (event) {
            is AuthEvent.TokenRejected, is AuthEvent.ProofRejected -> return
            is AuthEvent.SessionRejected -> base.copy(
                type = AuthEventRecord.Type.AuthenticationFailed,
                principal = event.principal,
                sessionId = event.sessionId,
                failureReason = event.reason.name,
            )
            is AuthEvent.ProofCredentialRejected -> base.copy(
                type = AuthEventRecord.Type.ProofRejected,
                principal = event.principal,
                failureReason = event.reason.name,
                method = event.method.via,
                methodProperty = event.method.property,
            )
            is AuthEvent.ProofAccepted -> base.copy(
                type = AuthEventRecord.Type.ProofAccepted,
                principal = event.principal,
                method = event.method.via,
                methodProperty = event.method.property,
            )
            is AuthEvent.ProofIssued -> base.copy(
                type = AuthEventRecord.Type.ProofIssued,
                principal = event.principal,
                method = event.method.via,
                methodProperty = event.method.property,
            )
        }
        origins.flush()
        table().insertOne(record)
    }
}
