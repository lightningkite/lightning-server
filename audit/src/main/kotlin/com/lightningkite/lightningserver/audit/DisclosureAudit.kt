package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.DatabaseTableRegistration
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.Table

/**
 * Every audit layer at once, for a deployment that wants all of them.
 *
 * A convenience over [AuditCore] plus [DisclosureLog], [DataAccessLog] and [AuthEventLog]. Include
 * the pieces directly instead when a deployment wants only some — the data access log in particular
 * is far more voluminous than the rest and is worth a deliberate decision:
 *
 * ```kotlin
 * val audit = path.path("audit") include AuditCore(database)
 * val disclosures = path.path("audit-disclosure") include DisclosureLog(audit)
 * // ...and no data access log
 * ```
 *
 * ## Failure behaviour differs per layer, and is not configurable
 * Disclosure and data access fail closed: the thing they guard must not happen unrecorded.
 * Authentication events fail open, because the event has already happened by the time it is reported
 * and throwing would only mask the original failure. There is deliberately no switch — a security
 * control with an off switch gets switched off during the first incident, and "was this deployment
 * fail-open at the time?" becomes a question whose answer lives in config rather than in the log.
 *
 * @property requests Who asked, from where, and when — what every other layer's `requestId` refers to.
 * @property disclosures One row per audited record that reached a client.
 * @property dataAccess One row per query against an audited model. Opt-in per model; see
 *   [dataAccessLogged].
 * @property authEvents One row per authentication event.
 * @property registry What every model id and every bit permanently mean.
 */
public class DisclosureAudit(
    database: Runtime<Database>,
    subjectKeys: Map<String, AuditSubjectKey<*>> = emptyMap(),
    requireSubjectKeys: Boolean = false,
) : ServerBuilder() {
    public val core: AuditCore =
        path.path("core") include AuditCore(database, subjectKeys, requireSubjectKeys)

    public val disclosureLog: DisclosureLog = path.path("disclosure") include DisclosureLog(core)
    public val dataAccessLog: DataAccessLog = path.path("data-access") include DataAccessLog(core)
    public val authEventLog: AuthEventLog = path.path("auth-events") include AuthEventLog(core)

    public val requests: DatabaseTableRegistration<RequestRecord> get() = core.requests
    public val registry: RuntimeDeferred<AuditRegistry> get() = core.registry
    public val disclosures: DatabaseTableRegistration<DisclosureRecord> get() = disclosureLog.disclosures
    public val dataAccess: DatabaseTableRegistration<DataAccessRecord> get() = dataAccessLog.dataAccess
    public val authEvents: DatabaseTableRegistration<AuthEventRecord> get() = authEventLog.authEvents
}

/** Convenience for a bundled [DisclosureAudit]; see [DataAccessLog.dataAccessLogged]. */
context(runtime: ServerRuntime)
public fun <T : Any> DisclosureAudit.dataAccessLogged(table: Table<T>): Table<T> =
    dataAccessLog.dataAccessLogged(table)
