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
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives telemetry recorded by client apps and hands it to the server's telemetry backend, so no
 * telemetry credentials ship in clients. Mount with `path.path("telemetry") module ClientTelemetryEndpoints()`.
 *
 * Anonymous clients are accepted. `user.id` comes from the caller's authentication; any `user.*`
 * attribute the client sent is dropped.
 */
public class ClientTelemetryEndpoints : ServerBuilder() {
    // The backend keeps state per owner and per metric name (meters, instruments), so client-chosen
    // names must not grow without bound. Spans' operation names only become a RED attribute value,
    // which the OpenTelemetry SDK already caps per instrument.
    private val acceptedNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    public val ingest: ApiHttpHandler<PathSpec0, HasId<*>?, TelemetryBatch, Unit> = path.path("ingest").post bind ApiHttpHandler(
        summary = "Ingest client telemetry",
        functionName = "ingest",
        auth = anyAuth or noAuth,
        implementation = { batch: TelemetryBatch ->
            val records = batch.spans.size + batch.logs.size + batch.metrics.size
            if (records > MAX_RECORDS) throw BadRequestException("A telemetry batch holds at most $MAX_RECORDS records; this one has $records.")

            val names = batch.spans.map { it.owner } + batch.metrics.flatMap { listOf(it.owner, it.name) }
            for (name in names) {
                if (!NAME_PATTERN.matches(name)) throw BadRequestException("Telemetry owner and metric names must match $NAME_PATTERN.")
            }
            val instruments = batch.spans.map { it.owner } + batch.metrics.flatMap { listOf(it.owner, "${it.owner}/${it.name}") }
            for (name in instruments) {
                if (name in acceptedNames) continue
                if (acceptedNames.size >= MAX_DISTINCT_NAMES) throw BadRequestException("This server already accepts $MAX_DISTINCT_NAMES distinct telemetry owners and metrics; '$name' is one too many.")
                acceptedNames += name
            }

            fun TelemetryAttributes.withoutUser() = filterKeys { !it.name.startsWith("user.") }
            val user = TelemetryAttributes { putIfNotNull(TelemetryKeys.User.id, authOrNull?.rawId) }
            serverRuntime.telemetryBackend.ingest(TelemetryBatch(
                resource = batch.resource.withoutUser() + user,
                sentAt = batch.sentAt,
                spans = batch.spans.map { it.copy(attributes = it.attributes.withoutUser()) },
                logs = batch.logs.map { it.copy(attributes = it.attributes.withoutUser()) },
                metrics = batch.metrics.map { it.copy(attributes = it.attributes.withoutUser()) },
            ))
        }
    )

    private companion object {
        const val MAX_RECORDS = 2000
        const val MAX_DISTINCT_NAMES = 500
        val NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
    }
}
