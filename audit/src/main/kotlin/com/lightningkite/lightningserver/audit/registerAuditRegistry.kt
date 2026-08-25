package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.definition.PreDeployTask
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.typed.ApiWebSocketHandler
import com.lightningkite.lightningserver.typed.registerTable
import com.lightningkite.services.database.Database
import kotlinx.serialization.descriptors.SerialDescriptor

/**
 * Registers the audit bit registry on this server and returns an accessor for it.
 *
 * Two tables record what every model id and every bit permanently mean, and a pre-deploy task
 * assigns ids to anything audited that lacks them. Assignment happens once per deploy rather than at
 * startup so that instances never race to allocate the same index.
 *
 * ```kotlin
 * object Server : ServerBuilder() {
 *     val database = setting("database", DatabaseSettings())
 *     val auditRegistry = database.registerAuditRegistry()
 * }
 * ```
 *
 * @return The assignments in force, loaded from the tables on first use and cached for the life of
 *   the process — they only change during a deploy.
 */
context(builder: ServerBuilder)
public fun Runtime<Database>.registerAuditRegistry(): RuntimeDeferred<AuditRegistry> {
    val models = registerTable("AuditModelRegistration", AuditModelRegistration.serializer())
    val fields = registerTable("AuditFieldRegistration", AuditFieldRegistration.serializer())

    with(builder) {
        path.path("assign-audit-bits") bind PreDeployTask(
            dependencies = { listOf(models.preDeployTask, fields.preDeployTask) },
        ) {
            reconcileAuditRegistry(models(), fields(), auditedModelsOnServer())
        }
    }

    return RuntimeDeferred.Cached(RuntimeDeferred { loadAuditRegistry(models(), fields()) })
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
