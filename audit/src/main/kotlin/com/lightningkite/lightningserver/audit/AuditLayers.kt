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

