package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.data.Request
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.typedoutput.TypedOutputInterceptor
import com.lightningkite.services.database.Table
import kotlinx.serialization.KSerializer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
@OptIn(ExperimentalUuidApi::class)
public class DisclosureLogInterceptor(
    registry: RuntimeDeferred<AuditRegistry>,
    private val table: Runtime<Table<DisclosureRecord>>,
    private val chain: Runtime<AuditChain>,
) : TypedOutputInterceptor {
    override val name: String = "DisclosureLog"

    /**
     * Built once per process, since the registry it reads only changes during a deploy. Holding it
     * here is also what makes the extractor's path cache worth having.
     */
    private val extractor = RuntimeDeferred.Cached(RuntimeDeferred { DisclosureExtractor(registry.await()) })

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
                // v7 so the row carries its own insert time; DisclosureRecord.at reads it back.
                // Not generateRequestId(): that names execution ids, and this is a row id.
                _id = Uuid.generateV7NonMonotonicAt(runtime.clock.now()),
                requestId = runtime.initiator.requestRecordId,
                modelId = it.modelId,
                fields0 = it.bits.fields0,
                fields1 = it.bits.fields1,
                recordId = it.recordId,
            )
        }
        table().insert(rows)
        // Folded after the write, deliberately. A record attested but not stored would be a chain
        // that vouches for something an auditor cannot read; the reverse — stored but not yet
        // attested — is the window 5.7.1 documents and accepts.
        val chain = chain()
        rows.forEach { chain.fold(auditHash(it.chainInput())) }
    }
}
