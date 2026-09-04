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
 * The foundation the other audit layers join to: who asked, from where, and when.
 *
 * Include this, then whichever layers a deployment actually wants:
 *
 * ```kotlin
 * object Server : ServerBuilder() {
 *     val database = setting("database", DatabaseSettings())
 *
 *     val audit = path.path("audit") include AuditCore(database)
 *     val disclosures = path.path("audit-disclosure") include DisclosureLog(audit)
 *     val authEvents = path.path("audit-auth") include AuthEventLog(audit)
 * }
 * ```
 *
 * Every other layer's records carry a `requestId` that points at a [RequestRecord] here, so this is
 * the one piece that is not optional once anything else is included.
 *
 * ## Why auditing is a switch and not a default
 *
 * Nothing audits until a layer is included, and [Audited] on a model does nothing on its own. The
 * circumvention this design guards against is an *endpoint* built without auditing, not a deployment
 * that chose not to audit — and once a layer is included, no endpoint escapes it. A per-deployment
 * switch is a decision made once, in the open; a per-endpoint one would be made a hundred times,
 * silently.
 *
 * ## Failure behaviour
 *
 * The opening write is fail-closed: a request whose record cannot be written does not proceed. The
 * completion write, which fills in outcome and duration, cannot be — the response has already been
 * produced by then, so there is nothing left to prevent. Each layer documents its own behaviour; see
 * `plans/audit-logging.md` 5.6.
 *
 * @property requests Who asked, from where, and when. What every other layer's `requestId` refers to.
 * @property registry What every model id and every bit permanently mean. Needed to read a disclosure:
 *   a [DisclosureRecord]'s bits are meaningless without it.
 */
public class AuditCore(
    internal val database: Runtime<Database>,
) : ServerBuilder() {
    public val requests: DatabaseTableRegistration<RequestRecord> =
        database.registerTable("AuditRequest", RequestRecord.serializer())

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

    private val claimedLayers = mutableSetOf<String>()

    /**
     * Registers a layer against this core, refusing a second attachment of the same one.
     *
     * Attaching a layer twice installs its writer twice, and every writer here appends to the
     * builder's interceptor list without deduplication — so the result is silently *two rows per
     * event*, with no error and no warning. A doubled audit log is worse than a missing one: it reads
     * as evidence of activity that did not happen.
     *
     * Reachable through ordinary public API: a deployment that includes a layer twice over the same
     * core, or includes one from a helper of its own and again by hand, attaches twice.
     */
    internal fun claim(layer: String) {
        if (!claimedLayers.add(layer)) throw IllegalStateException(
            "$layer is already attached to this AuditCore. Attaching a layer twice installs its " +
                "writer twice and silently doubles every row it produces. Include each layer once."
        )
    }

    init {
        install(RequestRecordInterceptor(requests))
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
internal fun auditedModelsOnServer(): Map<String, SerialDescriptor> = buildMap {
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
