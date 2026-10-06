package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.Runtime
import io.github.oshai.kotlinlogging.KotlinLogging
import com.lightningkite.lightningserver.auth.AuthEventReporter
import com.lightningkite.lightningserver.auth.AuthEventType
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.generateFromServerClock
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.insertOne
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Writes authentication events to the audit database and folds them into the tamper-evidence chain.
 *
 * ## Why this one does not fail closed
 * The disclosure and data access logs gate *disclosure*: the thing they guard must not happen unless
 * it was recorded, so they throw. An authentication event is different in kind — it has already
 * happened by the time it is reported, and is usually reported from a path that is itself rejecting
 * something. Throwing here would replace a clean "your login failed" with an unrelated server error
 * and lose the original reason. So a write failure is logged loudly and swallowed.
 *
 * That is a real weakening: an attacker who can make the audit database unavailable can make
 * authentication events go unrecorded while authentication still works. It is recorded in
 * `plans/audit-logging.md` 7.3 as a deliberate asymmetry rather than an oversight.
 */
private val authEventLogger = KotlinLogging.logger("com.lightningkite.lightningserver.audit.AuthEventLog")

public class AuthEventLogReporter(
    private val origins: OriginRecordInterceptor,
    private val table: Runtime<Table<AuthEventRecord>>,
) : AuthEventReporter {
    override val name: String = "AuthEventLog"

    context(runtime: ServerRuntime)
    override suspend fun report(
        type: AuthEventType,
        principal: String?,
        actor: String?,
        sessionId: String?,
        sourceIp: String?,
        userAgent: String?,
        detail: String?,
        method: String?,
        methodProperty: String?,
    ) {
        val record = AuthEventRecord(
            _id = AuthEventRecord.ID(UuidV7.generateFromServerClock()),
            requestId = OriginRecord.ID(runtime.execution.origin.toExternal()),
            type = type,
            principal = principal,
            actor = actor,
            sessionId = sessionId,
            sourceIp = sourceIp,
            userAgent = userAgent,
            failureReason = detail,
            method = method,
            methodProperty = methodProperty,
        )
        try {
            with(runtime) {
                origins.ensureRecorded()
                table().insertOne(record)
            }
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) currentCoroutineContext().ensureActive()
            authEventLogger.error(e) { "Failed to record auth event $type; authentication continued unrecorded." }
        }
    }
}
