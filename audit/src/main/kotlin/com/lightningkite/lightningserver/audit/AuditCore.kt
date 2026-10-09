package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.logger
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.typed.DatabaseTableRegistration
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.database.Database
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlin.uuid.Uuid

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
 * Every other layer's records carry a `requestId` that points at a [OriginRecord] here, so this is
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
 * @property originsTable Who asked, from where, and when. What every other layer's `requestId` refers to.
 * @property registry What every model id and every bit permanently mean. Needed to read a disclosure:
 *   a [DisclosureRecord]'s bits are meaningless without it.
 */
public class AuditCore<REQUEST_INFO>(
    internal val database: Runtime<Database>,
    requestInfoSerializer: KSerializer<REQUEST_INFO>,
    getInfo: suspend context(ServerRuntime) (Request<*>) -> REQUEST_INFO
) : ServerBuilder() {
    public val originsTable: DatabaseTableRegistration<OriginRecord<REQUEST_INFO>> =
        database.registerTable("AuditRequest", OriginRecord.serializer(requestInfoSerializer))

    public val registry: AuditRegistry = path.path("registry") include AuditRegistry(
        database.registerTable("AuditModelRegistration", AuditModelRegistration.serializer()),
        database.registerTable("AuditFieldRegistration", AuditFieldRegistration.serializer()),
    )

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

    public val origins: OriginRecording<REQUEST_INFO> = OriginRecording(
        originsTable,
        requestInfoSerializer,
        getInfo
    )

    init {
        origins.install()
    }
}

public fun AuditCore(database: Runtime<Database>): AuditCore<OriginRecord.StandardRequestInfo> =
    AuditCore(database, OriginRecord.StandardRequestInfo.serializer()) { request ->
        val auth = try {
            request[Authentication.CacheKey]?.let { it.fromMasquerade ?: it }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            serverRuntime.logger.error(e) { "Could not determine auth of request for origin recording" }
            null
        }
        OriginRecord.StandardRequestInfo(
            subjectId = auth?.rawId?.let(Uuid::parseOrNull),
            sessionId = auth?.sessionId?.let(Uuid::parseOrNull),
            sourceIp = request.sourceIp,
            engineRequestId = request.engineRequestId,
            upstreamRequestId = request.upstreamRequestId,
        )
    }
