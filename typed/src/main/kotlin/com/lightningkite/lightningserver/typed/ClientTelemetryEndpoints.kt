package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.anyAuth
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.auth.or
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.services.database.HasId
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryBatch
import com.lightningkite.services.telemetry.TelemetryKeys

/**
 * Receives telemetry recorded by client apps and hands it to the server's telemetry backend, so no
 * telemetry credentials ship in clients. Mount with `path.path("telemetry") module ClientTelemetryEndpoints()`.
 *
 * Anonymous clients are accepted. `user.id` comes from the caller's authentication; any `user.*`
 * attribute the client sent is dropped. Oversized or malformed batches are rejected whole with 400, not
 * truncated. Clients send no metrics, and ingested spans feed none: client-chosen values would drive metric
 * cardinality. The ingest request itself isn't traced.
 */
public class ClientTelemetryEndpoints : ServerBuilder() {
    public val ingest: ApiHttpHandler<PathSpec0, HasId<*>?, TelemetryBatch, Unit> = path.path("ingest").post bind object :
        ApiHttpHandler<PathSpec0, HasId<*>?, TelemetryBatch, Unit> by ApiHttpHandler(
            summary = "Ingest client telemetry",
            functionName = "ingest",
            auth = anyAuth or noAuth,
            implementation = { sent: TelemetryBatch ->
                val records = sent.spans.size + sent.logs.size
                if (records > MAX_RECORDS) throw BadRequestException("A telemetry batch holds at most $MAX_RECORDS records; this one has $records.")

                fun TelemetryAttributes.withoutUser() = filterKeys { !it.name.startsWith("user.") }
                val user = TelemetryAttributes { putIfNotNull(TelemetryKeys.User.id, authOrNull?.rawId) }
                val batch = TelemetryBatch(
                    resource = sent.resource.withoutUser() + user,
                    sentAt = sent.sentAt,
                    spans = sent.spans.map { it.copy(attributes = it.attributes.withoutUser()) },
                    logs = sent.logs.map { it.copy(attributes = it.attributes.withoutUser()) },
                )

                val attributeSets = listOf(batch.resource) + batch.spans.map { it.attributes } + batch.logs.map { it.attributes }
                if (attributeSets.any { it.map.size > MAX_ATTRIBUTES }) throw BadRequestException("A telemetry record holds at most $MAX_ATTRIBUTES attributes.")
                for (attributes in attributeSets) {
                    for ((key, value) in attributes.map) {
                        if (!NAME_PATTERN.matches(key.name)) throw BadRequestException("Telemetry attribute names must match $NAME_PATTERN; '${key.name}' doesn't.")
                        val strings = (value as? List<*>)?.filterIsInstance<String>() ?: listOfNotNull(value as? String)
                        if (strings.any { it.length > MAX_VALUE_LENGTH }) throw BadRequestException("Telemetry attribute '${key.name}' is longer than $MAX_VALUE_LENGTH characters.")
                    }
                }
                for (span in batch.spans) {
                    if (!NAME_PATTERN.matches(span.owner)) throw BadRequestException("Telemetry owner names must match $NAME_PATTERN; '${span.owner}' doesn't.")
                }

                val traceIds = batch.spans.map { it.traceId } + batch.logs.mapNotNull { it.traceId }
                val spanIds = batch.spans.flatMap { listOfNotNull(it.spanId, it.parentSpanId) } + batch.logs.mapNotNull { it.spanId }
                if (traceIds.any { !TRACE_ID.matches(it) } || spanIds.any { !SPAN_ID.matches(it) }) {
                    throw BadRequestException("Trace ids must be 32 and span ids 16 lowercase hex digits.")
                }

                serverRuntime.telemetryBackend.ingest(batch)
            }
        ) {
        // Every upload would add a span of its own, and uploads happen on every client all day.
        override val traced: Boolean get() = false
    }

    private companion object {
        const val MAX_RECORDS = 2000
        const val MAX_ATTRIBUTES = 128
        // Ingested records skip the server SDK's SpanLimits, so the endpoint bounds values itself.
        const val MAX_VALUE_LENGTH = 4096
        val NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
        val TRACE_ID = Regex("[0-9a-f]{32}")
        val SPAN_ID = Regex("[0-9a-f]{16}")
    }
}
