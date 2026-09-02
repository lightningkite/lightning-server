package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.PreDeployTask
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ApiWebSocketHandler
import com.lightningkite.lightningserver.typed.DatabaseTableRegistration
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.database.Database
import kotlinx.serialization.descriptors.SerialDescriptor

/**
 * Disclosure auditing: every audited record that reaches a client is recorded, and no endpoint can
 * opt out.
 *
 * Include it like any other module. The path it is mounted at namespaces its pre-deploy tasks; its
 * interceptors apply to the whole server regardless, since auditing that covered only part of a
 * server would not be auditing.
 *
 * ```kotlin
 * object Server : ServerBuilder() {
 *     val database = setting("database", DatabaseSettings())
 *     val audit = path.path("audit") include DisclosureAudit(database)
 * }
 * ```
 *
 * ## Why this is a switch and not a default
 *
 * Nothing audits until this is included, and [Audited] on a model does nothing on its own. The
 * circumvention this design guards against is an *endpoint* built without auditing, not a deployment
 * that chose not to audit — and once included, no endpoint escapes either the disclosure log or the
 * request log. A per-deployment switch is a decision made once, in the open; a per-endpoint one would
 * be made a hundred times, silently.
 *
 * ## What it costs
 *
 * Both writers are fail-closed, so an outage of [database] is an outage for every endpoint that
 * returns an audited model. That is the intended trade — see sections 5.6 and 5.8.1 of
 * `plans/audit-logging.md`.
 *
 * @property requests Who asked, from where, and when — what a [DisclosureRecord.requestId] refers to.
 * @property disclosures One row per audited record that reached a client.
 * @property dataAccess One row per query against an audited model, including privileged internal
 *   reads. Unlike the other two this is opt-in per model, because it attaches to a `ModelInfo`.
 * @property registry What every model id and every bit permanently mean. Needed to read the log:
 *   a [DisclosureRecord]'s bits are meaningless without it.
 */
public class DisclosureAudit(database: Runtime<Database>) : ServerBuilder() {
    public val requests: DatabaseTableRegistration<RequestRecord> =
        database.registerTable("AuditRequest", RequestRecord.serializer())

    public val disclosures: DatabaseTableRegistration<DisclosureRecord> =
        database.registerTable("AuditDisclosure", DisclosureRecord.serializer())

    /**
     * Every query issued against an audited model — the layer that closes the aggregation and oracle
     * channels the disclosure log cannot see. Only populated for models whose `ModelInfo` passes
     * [dataAccessLogged]; see `plans/audit-logging.md` 6.2.
     */
    public val dataAccess: DatabaseTableRegistration<DataAccessRecord> =
        database.registerTable("AuditDataAccess", DataAccessRecord.serializer())

    private val registrations: DatabaseTableRegistration<AuditModelRegistration> =
        database.registerTable("AuditModelRegistration", AuditModelRegistration.serializer())

    private val fieldRegistrations: DatabaseTableRegistration<AuditFieldRegistration> =
        database.registerTable("AuditFieldRegistration", AuditFieldRegistration.serializer())

    /**
     * Assigns permanent ids and bit indices to anything audited that lacks them.
     *
     * A pre-deploy task rather than a startup task, so assignment happens once per deploy and
     * instances never race to allocate the same index. It is convergent, so re-running it on every
     * deploy — which is what the framework does — is a no-op.
     */
    private val assignBits: PreDeployTask = path.path("assign-audit-bits") bind PreDeployTask(
        dependencies = { listOf(registrations.preDeployTask, fieldRegistrations.preDeployTask) },
    ) {
        reconcileAuditRegistry(registrations(), fieldRegistrations(), auditedModelsOnServer())
    }

    /**
     * Loaded from the tables on first use and cached for the life of the process, which is correct
     * because assignments only ever change during a deploy.
     */
    public val registry: RuntimeDeferred<AuditRegistry> =
        RuntimeDeferred.Cached(RuntimeDeferred { loadAuditRegistry(registrations(), fieldRegistrations()) })

    init {
        install(RequestRecordInterceptor(requests))
        install(DisclosureLogInterceptor(registry, disclosures))
    }
}

/**
 * Every audited model this server can disclose, found by walking the serializers its endpoints
 * declare.
 *
 * **Endpoints only, deliberately.** An earlier version also scanned the registered tables, which
 * picked up a few models that reach clients through a serializer too dynamic to resolve statically —
 * but only when the model happened to be a table. Inconsistent coverage is worse than none here: it
 * hides the gap instead of failing on it. A model no endpoint's serializer reaches gets no bits, and
 * disclosing it fails the request — see [AuditRegistry.modelId].
 *
 * Auditing keys off serializers throughout, never tables. A disclosure is observed with a serializer
 * in hand and nothing else, so the serializer is the only thing that can be detected consistently.
 */
context(server: ServerRuntime)
private fun auditedModelsOnServer(): Map<String, SerialDescriptor> = buildMap {
    for (endpoints in server.server.endpoints.values) {
        for (handler in endpoints.http.values) {
            if (handler !is ApiHttpHandler<*, *, *, *>) continue
            putAll(handler.inputType.descriptor.auditedModels())
            putAll(handler.outputType.descriptor.auditedModels())
        }
        val socket = endpoints.webSocket
        if (socket is ApiWebSocketHandler<*, *, *, *, *>) {
            putAll(socket.inputType.descriptor.auditedModels())
            putAll(socket.outputType.descriptor.auditedModels())
        }
    }
}
