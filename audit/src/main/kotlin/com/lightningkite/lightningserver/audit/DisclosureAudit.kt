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
import com.lightningkite.services.database.insertOne
import com.lightningkite.lightningserver.definition.ScheduledTask
import com.lightningkite.lightningserver.runtime.now
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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
public class DisclosureAudit(
    database: Runtime<Database>,
    /**
     * How often the tamper-evidence chain is sealed. Also the window in which a crash leaves recent
     * records unattested — shorter is safer and costs one row per interval. See 5.7.1.
     */
    private val sealInterval: Duration = 1.minutes,
    /**
     * Erasure subject per audited model, keyed by serial name. See [AuditSubjectKey].
     *
     * Absent by default, which is correct for the US regime this targets first. Supplying keys does
     * **not** by itself encrypt anything — crypto-shredding is not implemented (11.2). What they do
     * today is satisfy [requireSubjectKeys], so a deployment that will need erasure can prove it has
     * made the decision for every audited model *before* records exist to be shredded.
     */
    private val subjectKeys: Map<String, AuditSubjectKey<*>> = emptyMap(),
    /**
     * Refuse to deploy unless every audited model has an entry in [subjectKeys].
     *
     * Exists because the erasure decision cannot be retrofitted: records written before a key is
     * registered were written unwrapped and stay unshreddable forever. A deployment that may ever
     * face an erasure request should turn this on from its first deploy, so that adding an audited
     * model without deciding its subject fails the deploy rather than silently producing records that
     * can never be erased.
     */
    private val requireSubjectKeys: Boolean = false,
) : ServerBuilder() {
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

    /**
     * The tamper-evidence chain. Sealed periodically rather than per record; see
     * `plans/audit-logging.md` 5.7.1 and [AuditChain].
     */
    public val totalLog: DatabaseTableRegistration<TotalLogEntry> =
        database.registerTable("AuditTotalLog", TotalLogEntry.serializer())

    /**
     * This process's chain head.
     *
     * One per process — see [TotalLogEntry.chainId]. Created lazily against the engine's identity so
     * that two runtimes built from the same definition do not share a head.
     */
    public val chain: Runtime<AuditChain> = Runtime.Cached(Runtime {
        AuditChain(chainId = "${'$'}{serverId}-${'$'}{clock.now().toEpochMilliseconds()}")
    })

    /** Authentication events — the history a mutable session row cannot provide. See 7.1. */
    public val authEvents: DatabaseTableRegistration<AuthEventRecord> =
        database.registerTable("AuditAuthEvent", AuthEventRecord.serializer())

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
    /**
     * Fails the deploy when [requireSubjectKeys] is on and an audited model has no erasure subject.
     *
     * A pre-deploy task specifically so it runs *before* the new version can write anything: once a
     * record exists it is too late to decide how it should have been encrypted.
     */
    private val checkSubjectKeys: PreDeployTask = path.path("check-audit-subject-keys") bind PreDeployTask {
        if (!requireSubjectKeys) return@PreDeployTask
        val missing = auditedModelsOnServer().keys.filter { it !in subjectKeys }.sorted()
        if (missing.isNotEmpty()) throw IllegalStateException(
            "requireSubjectKeys is on, but these audited models have no AuditSubjectKey: " +
                missing.joinToString(", ") + ". The erasure decision cannot be made after records " +
                "exist — see plans/audit-logging.md 11.2 — so this fails the deploy rather than " +
                "producing records that can never be erased."
        )
    }

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

    /**
     * Turns everything folded since the last seal into a chain entry.
     *
     * On a schedule rather than per record because folding is in-memory and cheap while sealing is a
     * write; see [AuditChain]. The interval is the window in which a crash leaves recent records
     * unattested — present in the queryable log, covered by no entry.
     */
    public val sealChain: ScheduledTask = path.path("seal-audit-chain") bind ScheduledTask(
        frequency = sealInterval,
    ) {
        chain().seal(now().toEpochMilliseconds())?.let { totalLog().insertOne(it) }
    }

    init {
        install(RequestRecordInterceptor(requests))
        install(DisclosureLogInterceptor(registry, disclosures, chain))
        install(AuthEventLogReporter(authEvents, chain))
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
