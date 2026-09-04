package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.DatabaseTableRegistration
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.database.Table

/**
 * Layer 2: one row per audited record that reaches a client, and no endpoint can opt out.
 *
 * The question this answers is "who has seen this record", which nothing below the typed layer can
 * answer — by the time a value reaches the database layer it is a query, not a disclosure.
 *
 * ## Failure behaviour: fail-closed
 * A disclosure that cannot be recorded does not happen. The interception point sits before
 * serialization precisely so that throwing there prevents the body from ever being built. An outage
 * of the audit database is therefore an outage for every endpoint that returns an audited model.
 * That is the intended trade — see `plans/audit-logging.md` 5.6.
 *
 * @property disclosures One row per audited record that reached a client.
 */
public class DisclosureLog(private val core: AuditCore) : ServerBuilder() {
    public val disclosures: DatabaseTableRegistration<DisclosureRecord> =
        core.database.registerTable("AuditDisclosure", DisclosureRecord.serializer())

    init {
        core.claim("DisclosureLog")
        install(DisclosureLogInterceptor(core.registry, disclosures))
    }
}

/**
 * Layer 3: one row per query against an audited model, including privileged internal reads.
 *
 * This is the expensive layer, and the only one that is opt-in per model — see [dataAccessLogged].
 * That is deliberate rather than an inconsistency: it writes a row per *query* rather than per
 * disclosure, so on a read-heavy model it is orders of magnitude more voluminous than everything else
 * here combined. Enable it where the aggregation and oracle channels in `audit-logging.md` 6.1
 * actually matter, not everywhere by reflex.
 *
 * ## Failure behaviour: fail-closed, with the widest blast radius here
 * A query whose record cannot be written does not run. Because this sits at the database layer it
 * covers privileged internal reads too — startup tasks, schedule ticks, one service reading another's
 * model — so an audit outage stops more than request serving. See `audit-logging.md` 6.2 and the risk
 * register.
 *
 * Unlike the other layers this installs no interceptor: it attaches per model through `log`.
 *
 * @property dataAccess One row per query against an audited model.
 */
public class DataAccessLog(private val core: AuditCore) : ServerBuilder() {
    public val dataAccess: DatabaseTableRegistration<DataAccessRecord> =
        core.database.registerTable("AuditDataAccess", DataAccessRecord.serializer())

    internal val registry get() = core.registry

    init {
        core.claim("DataAccessLog")
    }
}

/**
 * Layer 4: authentication events — the history a mutable session row cannot provide.
 *
 * A `Session` row records that a session *exists*, not that a login *happened*. Everything the survey
 * in `audit-logging.md` 7.1 found was last-write-wins state, an ephemeral counter deleted on the next
 * success, or a debug `println`.
 *
 * ## Failure behaviour: fail-open, deliberately and uniquely
 * Unlike the layers above, this one logs and swallows a write failure. Those gate *disclosure*, so the
 * guarded thing must not happen unrecorded. An authentication event has already happened by the time
 * it is reported, and is usually reported from a path that is itself rejecting something — throwing
 * would replace a clean "your login failed" with an unrelated server error and lose the original
 * reason. The cost, stated plainly: an attacker who can make the audit database unavailable can make
 * authentication events go unrecorded while authentication keeps working. See 7.3.1.
 *
 * @property authEvents One row per authentication event.
 */
public class AuthEventLog(private val core: AuditCore) : ServerBuilder() {
    public val authEvents: DatabaseTableRegistration<AuthEventRecord> =
        core.database.registerTable("AuditAuthEvent", AuthEventRecord.serializer())

    init {
        core.claim("AuthEventLog")
        install(AuthEventLogReporter(authEvents))
    }
}

/**
 * Layer 5: one row per audited record that changed, and what it changed from.
 *
 * The question this answers is "who changed this record, and to what" — tampering rather than
 * snooping. Nothing else here can: a [DataAccessRecord] carries the `Modification` that was
 * *submitted*, which is intent, not effect. It does not say which rows a condition matched, and a
 * modification such as `count assign count + 1` names no resulting value at all.
 *
 * A separate layer with its own table rather than more columns on the data access log, because
 * looking for tampering and looking for query abuse are unrelated investigations with unrelated
 * volumes; a deployment should be able to install either without paying for the other. Opt-in per
 * model, like the data access log — see [mutationLogged].
 *
 * ## Failure behaviour: fail-open, and necessarily so
 * The layers that gate disclosure record first and throw. That option does not exist here: the effect
 * *is* the record, so there is nothing to write until the change is made, and once it is made,
 * throwing reports failure for something that happened — inviting a retry that applies it twice. A
 * failed audit write is therefore logged loudly and the mutation stands. Where "no unrecorded write"
 * matters more than "no double write", install [DataAccessLog] alongside: it fails closed and records
 * the attempt before it runs.
 *
 * @param bulkDetail Whether the `Ignoring*` methods are upgraded so every changed row is recorded.
 *   Defaults to recording everything; see [BulkMutationDetail] for what the alternative gives up.
 * @property mutations One row per change to an audited record.
 */
public class MutationLog(
    private val core: AuditCore,
    internal val bulkDetail: BulkMutationDetail = BulkMutationDetail.RecordEveryRow,
) : ServerBuilder() {
    public val mutations: DatabaseTableRegistration<MutationRecord> =
        core.database.registerTable("AuditMutation", MutationRecord.serializer())

    internal val registry get() = core.registry

    init {
        core.claim("MutationLog")
    }
}
