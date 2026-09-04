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

