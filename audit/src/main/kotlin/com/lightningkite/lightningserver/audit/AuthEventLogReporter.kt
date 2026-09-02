package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.Runtime
import io.github.oshai.kotlinlogging.KotlinLogging
import com.lightningkite.lightningserver.runtime.AuthEventReporter
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.insertOne
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

@OptIn(ExperimentalUuidApi::class)
public class AuthEventLogReporter(
    private val table: Runtime<Table<AuthEventRecord>>,
) : AuthEventReporter {
    override val name: String = "AuthEventLog"

    context(runtime: ServerRuntime)
    override suspend fun report(
        type: String,
        principal: String?,
        actor: String?,
        sessionId: String?,
        sourceIp: String?,
        userAgent: String?,
        detail: String?,
    ) {
        val parsed = AuthEventType.entries.firstOrNull { it.name == type }
        if (parsed == null) {
            authEventLogger.error { "Unknown auth event type \"$type\"; not recorded." }
            return
        }
        val record = AuthEventRecord(
            _id = Uuid.generateV7NonMonotonicAt(runtime.clock.now()),
            requestId = runtime.initiator.requestRecordId,
            type = parsed,
            principal = principal,
            actor = actor,
            sessionId = sessionId,
            sourceIp = sourceIp,
            userAgent = userAgent,
            failureReason = detail,
        )
        try {
            with(runtime) { table().insertOne(record) }
        } catch (e: Exception) {
            // Cancellation is the caller being torn down, not a sink failure. Swallowing it here
            // would report this coroutine as having completed normally and break structured
            // concurrency on every cancelled request.
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            authEventLogger.error(e) { "Failed to record auth event $parsed; authentication continued unrecorded." }
        }
    }
}
