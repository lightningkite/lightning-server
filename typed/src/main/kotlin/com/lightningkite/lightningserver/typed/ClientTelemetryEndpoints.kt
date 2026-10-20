package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.anyAuth
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.auth.or
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.ratelimit.RateLimitInterceptor
import com.lightningkite.lightningserver.ratelimit.RateLimitSettings
import com.lightningkite.lightningserver.ratelimit.RequestLimits
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.HasId
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryBatch
import com.lightningkite.services.telemetry.TelemetryKeys
import kotlin.time.Duration.Companion.seconds

/**
 * Receives telemetry recorded by client apps and hands it to the server's telemetry backend, so no
 * telemetry credentials ship in clients. Mount with `path.path("telemetry") module ClientTelemetryEndpoints(cache)`.
 *
 * The server decides who the records are from, so a client can't pose as the server or another user: the
 * resource is `service.name = "<projectName>-client"`, the caller's `user.id` (absent for anonymous
 * callers), and only the [CLIENT_RESOURCE_KEYS] the client sent. Client-sent `user.*` span and log attributes
 * are dropped too.
 *
 * Uploads are rate limited per signed-in user, else per IP, with [RateLimitInterceptor] keeping its state in
 * [cache]. Set the `clientTelemetryRateLimit` setting to null to turn that off. Oversized or malformed batches are rejected whole with 400, not
 * truncated. Clients send no metrics, and ingested spans feed none: client-chosen values would drive metric
 * cardinality. The ingest request itself isn't traced.
 */
public class ClientTelemetryEndpoints(cache: Runtime<Cache>) : ServerBuilder() {
    public val ingest: ApiHttpHandler<PathSpec0, HasId<*>?, TelemetryBatch, Unit> = path.path("ingest").post bind object :
        ApiHttpHandler<PathSpec0, HasId<*>?, TelemetryBatch, Unit> by ApiHttpHandler(
            summary = "Ingest client telemetry",
            functionName = "ingest",
            auth = anyAuth or noAuth,
            implementation = { sent: TelemetryBatch ->
                val records = sent.spans.size + sent.logs.size
                if (records > MAX_RECORDS) throw BadRequestException("A telemetry batch holds at most $MAX_RECORDS records; this one has $records.")

                fun TelemetryAttributes.withoutUser() = filterKeys { !it.name.startsWith("user.") }
                val resource = TelemetryAttributes {
                    putAll(sent.resource.filterKeys { it.name in CLIENT_RESOURCE_KEYS })
                    put(TelemetryKeys.Service.name, "${serverRuntime.projectName}-client")
                    putIfNotNull(TelemetryKeys.User.id, authOrNull?.rawId)
                }
                val batch = TelemetryBatch(
                    resource = resource,
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

    private val rateLimit = setting<RateLimitSettings?>("clientTelemetryRateLimit", RateLimitSettings(rateLimiterId = "client-telemetry"))

    init {
        // Interceptors apply to the whole server, so this one meters only [ingest].
        install(RateLimitInterceptor(rateLimit, cache) { request ->
            val handler = (request as? HttpRequest<*>)?.path?.tryResolve()?.value
            if (handler !== ingest) return@RateLimitInterceptor null
            val auth = request[Authentication.CacheKey]
            RequestLimits(
                key = auth?.let { "user:${it.principalName}:${it.rawId}" } ?: "ip:${request.sourceIp}",
                // Each upload costs 5 s of budget whatever it takes, and 120 s may be borrowed ahead: a burst of
                // about 24 uploads (draining an outbox saved while offline), then one per 5 s. Clients normally
                // upload every 30 s, so a healthy client never sees a 429; a hostile one is held to 12 a minute.
                multiplier = 1.0,
                overhead = 5.seconds,
                borrowTime = 5.seconds,
                leeway = 120.seconds,
            )
        })
    }

    public companion object {
        /** The only resource attributes kept from the client; the server sets the rest. */
        public val CLIENT_RESOURCE_KEYS: Set<String> = setOf("service.version", "os.type", "session.id", "app.debug")

        private const val MAX_RECORDS = 2000
        private const val MAX_ATTRIBUTES = 128
        // Ingested records skip the server SDK's SpanLimits, so the endpoint bounds values itself.
        private const val MAX_VALUE_LENGTH = 4096
        private val NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_.-]{0,63}")
        private val TRACE_ID = Regex("[0-9a-f]{32}")
        private val SPAN_ID = Regex("[0-9a-f]{16}")
    }
}
