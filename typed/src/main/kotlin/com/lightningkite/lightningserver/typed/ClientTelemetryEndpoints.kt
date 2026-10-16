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
 * attribute the client sent is dropped.
 */
public class ClientTelemetryEndpoints : ServerBuilder() {
    // The backend keeps state per owner, per metric and per attribute key name (meters, instruments,
    // cached keys), so client-chosen names must not grow without bound. Span operation names and attribute
    // values only become RED attribute values, which the OpenTelemetry SDK already caps per instrument.
    private val acceptedNames = HashSet<String>()

    public val ingest: ApiHttpHandler<PathSpec0, HasId<*>?, TelemetryBatch, Unit> = path.path("ingest").post bind ApiHttpHandler(
        summary = "Ingest client telemetry",
        functionName = "ingest",
        auth = anyAuth or noAuth,
        implementation = { sent: TelemetryBatch ->
            val records = sent.spans.size + sent.logs.size + sent.metrics.size
            if (records > MAX_RECORDS) throw BadRequestException("A telemetry batch holds at most $MAX_RECORDS records; this one has $records.")

            fun TelemetryAttributes.withoutUser() = filterKeys { !it.name.startsWith("user.") }
            val user = TelemetryAttributes { putIfNotNull(TelemetryKeys.User.id, authOrNull?.rawId) }
            val batch = TelemetryBatch(
                resource = sent.resource.withoutUser() + user,
                sentAt = sent.sentAt,
                spans = sent.spans.map { it.copy(attributes = it.attributes.withoutUser()) },
                logs = sent.logs.map { it.copy(attributes = it.attributes.withoutUser()) },
                metrics = sent.metrics.map { it.copy(attributes = it.attributes.withoutUser()) },
            )

            val attributeSets = listOf(batch.resource) + batch.spans.map { it.attributes } + batch.logs.map { it.attributes } + batch.metrics.map { it.attributes }
            if (attributeSets.any { it.map.size > MAX_ATTRIBUTES }) throw BadRequestException("A telemetry record holds at most $MAX_ATTRIBUTES attributes.")

            val traceIds = batch.spans.map { it.traceId } + batch.logs.mapNotNull { it.traceId }
            val spanIds = batch.spans.flatMap { listOfNotNull(it.spanId, it.parentSpanId) } + batch.logs.mapNotNull { it.spanId }
            if (traceIds.any { !TRACE_ID.matches(it) } || spanIds.any { !SPAN_ID.matches(it) }) {
                throw BadRequestException("Trace ids must be 32 and span ids 16 lowercase hex digits.")
            }

            val owners = batch.spans.map { it.owner } + batch.metrics.map { it.owner }
            val keys = attributeSets.flatMap { attributes -> attributes.keys.map { it.name } }
            for (name in owners + batch.metrics.map { it.name } + keys) {
                if (!NAME_PATTERN.matches(name)) throw BadRequestException("Telemetry owner, metric and attribute names must match $NAME_PATTERN; '$name' doesn't.")
            }
            val names = owners + batch.metrics.map { "${it.owner}/${it.name}" } + keys
            synchronized(acceptedNames) {
                val unseen = names.toSet() - acceptedNames
                if (acceptedNames.size + unseen.size > MAX_DISTINCT_NAMES) {
                    throw BadRequestException("This server accepts at most $MAX_DISTINCT_NAMES distinct telemetry owner, metric and attribute names.")
                }
                acceptedNames += unseen
            }

            serverRuntime.telemetryBackend.ingest(batch)
        }
    )

    private companion object {
        const val MAX_RECORDS = 2000
        const val MAX_ATTRIBUTES = 128
        const val MAX_DISTINCT_NAMES = 1000
        val NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
        val TRACE_ID = Regex("[0-9a-f]{32}")
        val SPAN_ID = Regex("[0-9a-f]{16}")
    }
}
