package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.database.Database

/**
 * Sets up the auditing system's shared parts, [origins] and [registry], and builds each optional log on request.
 *
 * Extend it with an object and call the builder for each log it should include. A builder only constructs its
 * log and registers the log's table; it never installs it, so every installation stays visible where it
 * happens. Calling a builder again returns the same log.
 *
 * ```kotlin
 * object Server : ServerBuilder() {
 *     val database = setting("database", Database.Settings())
 *
 *     object Audit : AuditingBuilder.WithStandardRequestInfo(database) {
 *         val disclosures = install(AuditDisclosureLog())
 *         val authEvents = install(AuthEventLog())
 *         val mutations = AuditMutationLog()
 *     }
 *
 *     val audit = path.path("audit") include Audit
 * }
 * ```
 *
 * [AuditDisclosureLog] and [AuthEventLog] record nothing until they are installed. [AuditMutationLog] and
 * [AuditQueryLog] are not installed; instead each table they should record is wrapped with
 * [logAuditedFieldChanges] or [logQueries].
 *
 * This is only a convenience. Constructing each log yourself is equally valid; either way, install each log at
 * most once, or every event it records is written twice.
 */
public abstract class AuditingBuilder<ORIGIN_REQUEST_INFO>(
    private val database: Runtime<Database>,
    originRequestInfo: OriginRecording.RequestInfo<ORIGIN_REQUEST_INFO>
) : ServerBuilder() {
    public abstract class WithStandardRequestInfo(database: Runtime<Database>) : AuditingBuilder<OriginRecord.StandardRequestInfo>(
        database,
        OriginRecording.RequestInfo.Standard
    )

    public val origins: OriginRecording<ORIGIN_REQUEST_INFO> = OriginRecording(
        table = database.registerTable("Audit_OriginRecords", OriginRecord.serializer(originRequestInfo.serializer)),
        info = originRequestInfo
    )

    init {
        install(origins)
    }

    public val registry: AuditRegistry = path.path("registry") include AuditRegistry(
        models = database.registerTable("Audit_ModelRegistrations"),
        fields = database.registerTable("Audit_FieldRegistrations"),
    )

    private val disclosureLog by lazy {
        AuditDisclosureLog(origins, registry, database.registerTable("Audit_DisclosureRecords"))
    }

    private val mutationLog by lazy {
        AuditMutationLog(origins, registry, database.registerTable("Audit_MutationRecords"))
    }

    private val queryLog by lazy {
        AuditQueryLog(origins, registry, database.registerTable("Audit_QueryRecords"))
    }

    private val authEventLog by lazy {
        AuthEventLog(origins, database.registerTable("Audit_AuthEventRecords"))
    }

    /**
     * Returns this builder's [AuditQueryLog], building it and registering its table on the first call.
     *
     * The log records nothing on its own; wrap each table it should record with [logQueries].
     */
    protected fun AuditQueryLog(): AuditQueryLog = queryLog

    /**
     * Returns this builder's [AuditMutationLog], building it and registering its table on the first call.
     *
     * The log records nothing on its own; wrap each table it should record with [logAuditedFieldChanges].
     */
    protected fun AuditMutationLog(): AuditMutationLog = mutationLog

    /**
     * Returns this builder's [AuditDisclosureLog], building it and registering its table on the first call.
     *
     * The log records nothing until it is installed with `install(AuditDisclosureLog())`.
     */
    protected fun AuditDisclosureLog(): AuditDisclosureLog = disclosureLog

    /**
     * Returns this builder's [AuthEventLog], building it and registering its table on the first call.
     *
     * The log records nothing until it is installed with `install(AuthEventLog())`.
     */
    protected fun AuthEventLog(): AuthEventLog = authEventLog
}
