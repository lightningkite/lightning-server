package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typedoutput.TypedOutputInterceptor
import com.lightningkite.services.data.UuidV7
import com.lightningkite.services.database.Table
import kotlinx.serialization.KSerializer
import kotlin.uuid.ExperimentalUuidApi

/**
 * Writes one [DisclosureRecord] for every audited record that reaches a client.
 *
 * ## Fail-closed
 *
 * Nothing is caught here on purpose. An extraction that cannot resolve a model, a record with no
 * `_id`, or a sink that will not accept the write all propagate out of
 * [TypedOutputInterceptor.outputProduced], which aborts the send before the value is serialized. A
 * disclosure that could not be recorded does not happen.
 *
 * The practical consequence is worth stating plainly: an outage of the audit database is an outage
 * of every endpoint that returns an audited model.
 */
public class DisclosureLogInterceptor(
    registry: AuditRegistry,
    private val origins: OriginRecordInterceptor<*>,
    private val table: Runtime<Table<DisclosureRecord>>,
) : TypedOutputInterceptor {
    override val name: String = "DisclosureLog"

    /**
     * Built once per process, since the registry it reads only changes during a deploy. Holding it
     * here is also what makes the extractor's path cache worth having.
     */
    private val extractor = RuntimeDeferred.Cached { DisclosureExtractor(registry.assignments.await()) }

    context(runtime: ServerRuntime)
    override suspend fun <T> outputProduced(request: Request<*>, serializer: KSerializer<T>, value: T) {
        val disclosures = extractor.await().extract(
            serializer = serializer,
            value = value,
            serializersModule = runtime.externalSerialization.serializersModule,
        )
        if (disclosures.isEmpty()) return

        val rows = disclosures.map {
            DisclosureRecord(
                _id = DisclosureRecord.ID(UuidV7.generateNonMonotonicAt(runtime.clock.now())),
                requestId = OriginRecord.ID(runtime.execution.origin.toExternal()),
                recordType = it.modelId,
                recordId = it.recordId,
                disclosed = it.bits,
            )
        }
        origins.flush()
        table().insert(rows)
    }
}
