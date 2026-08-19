package com.lightningkite.lightningserver.typed

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.Namespaced
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Database
import com.lightningkite.services.telemetry.Counter
import com.lightningkite.services.telemetry.Histogram
import com.lightningkite.services.telemetry.InFlight
import com.lightningkite.services.telemetry.Lease
import com.lightningkite.services.telemetry.LogLevel
import com.lightningkite.services.telemetry.MetricUnit
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryBackend
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.telemetry.TelemetryTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Verifies that per-sub-request instrumentation inside the bulk endpoint mirrors the standard
 * HTTP root-span instrumentation: each sub-request gets a span named "$METHOD $route" with the
 * standard http.* attributes and a captured http.status_code, including error cases.
 */
class BulkSpanTest {

    /**
     * A minimal in-memory [TelemetryBackend] that records each finished span — its name, the merged
     * (initial + enriched) attributes keyed by attribute name, and whether the action completed
     * successfully — into an inspectable list. This circumvents OpenTelemetry entirely: the test
     * asserts directly against the recorded spans, with no OTEL SDK or exporter involved.
     */
    private object Recording : TelemetryBackend {
        data class RecordedSpan(val name: String, val attributes: Map<String, Any?>, val ok: Boolean)

        @Volatile
        var spans: MutableList<RecordedSpan> = Collections.synchronizedList(mutableListOf())

        init {
            TelemetryBackend.Settings.register("memory") { _, _, _ -> Recording }
        }

        override suspend fun <T> span(
            owner: Namespaced,
            opName: String,
            attributes: TelemetryAttributes,
            dimensions: Set<TelemetryKey<*>>,
            action: suspend (TelemetryTrace) -> T,
        ): T {
            val enriched = LinkedHashMap<TelemetryKey<*>, Any?>()
            val trace = object : TelemetryTrace {
                override fun enrich(attributes: TelemetryAttributes) { enriched.putAll(attributes.map) }
                override fun isLoggable(level: LogLevel): Boolean = false
                override fun log(level: LogLevel, message: String, attributes: TelemetryAttributes) {}
            }
            var ok = true
            try {
                return action(trace)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ok = false
                throw e
            } finally {
                val merged = (attributes.map + enriched).mapKeys { it.key.name }
                spans.add(RecordedSpan("${owner.name}.$opName", merged, ok))
            }
        }

        override fun histogram(owner: Namespaced, name: String, unit: MetricUnit, dimensions: Set<TelemetryKey<*>>): Histogram =
            object : Histogram { override suspend fun record(amount: Double) {} }
        override fun counter(owner: Namespaced, name: String, unit: MetricUnit, dimensions: Set<TelemetryKey<*>>): Counter =
            object : Counter { override suspend fun increment(amount: Double) {} }
        override fun inFlight(owner: Namespaced, name: String, dimensions: Set<TelemetryKey<*>>): InFlight =
            object : InFlight { override suspend fun lease(): Lease = object : Lease { override fun release() {} } }
        override fun gauge(owner: Namespaced, name: String, unit: MetricUnit, attributes: TelemetryAttributes, sample: () -> Long): AutoCloseable =
            AutoCloseable {}
        override fun reportError(throwable: Throwable, attributes: TelemetryAttributes) {}
    }

    object TestServer : ServerBuilder() {
        init {
            registerBasicMediaTypeCoders()
        }

        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())

        val ok = path.path("ok").get bind ApiHttpHandler(
            summary = "Ok",
            auth = noAuth,
            implementation = { _: Unit -> "ok" }
        )

        val notFoundEndpoint = path.path("missing").get bind ApiHttpHandler<_, _, Unit, String>(
            summary = "Missing",
            auth = noAuth,
            implementation = { _: Unit -> throw NotFoundException("nope") }
        )

        val meta = path.path("meta") include MetaEndpoints(
            packageName = "com.lightningkite.lightningserver.typed",
            database = database,
            cache = cache,
        )
    }

    @Test
    fun bulk_sub_requests_get_per_request_http_spans() {
        Recording.spans = Collections.synchronizedList(mutableListOf())
        TestServer.test(
            settings = {
                Recording  // ensure the "memory" URL scheme is registered
                telemetrySettings.set(TelemetryBackend.Settings(url = "memory"))
            }
        ) {
            runBlocking {
                serverRuntime.handle(
                    HttpRequest<PathSpec>(
                        path = RawHttpEndpoint(asString = "/meta/bulk", method = HttpMethod.POST),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders.EMPTY,
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                        requestId = generateRequestId(),
                        body = TypedData.text(
                            """
                            {
                              "okCall":      {"path":"/ok","method":"GET"},
                              "missingCall": {"path":"/missing","method":"GET"}
                            }
                            """.trimIndent(),
                            MediaType.Application.Json,
                        ),
                    )
                )
            }

            val spans = Recording.spans.toList()

            val okSpan = spans.singleOrNull { it.name == "lightningserver.GET /ok" }
                ?: fail("Expected per-sub-request span 'lightningserver.GET /ok'. Got: ${spans.map { it.name }}")
            assertEquals("GET", okSpan.attributes["http.method"])
            assertEquals("/ok", okSpan.attributes["http.route"])
            assertEquals("/ok", okSpan.attributes["http.target"])
            assertEquals(200L, okSpan.attributes["http.status_code"])

            val missingSpan = spans.singleOrNull { it.name == "lightningserver.GET /missing" }
                ?: fail("Expected per-sub-request span 'lightningserver.GET /missing'. Got: ${spans.map { it.name }}")
            assertEquals(
                404L,
                missingSpan.attributes["http.status_code"],
                "Sub-request that threw NotFoundException should record http.status_code = 404",
            )

            // The bulk request itself still produces its own root span and reports success
            // because the endpoint always returns a 200 with per-sub-request results in the body.
            val bulkRoot = spans.singleOrNull { it.name == "lightningserver.POST /meta/bulk" }
                ?: fail("Expected root span 'lightningserver.POST /meta/bulk'. Got: ${spans.map { it.name }}")
            assertEquals(200L, bulkRoot.attributes["http.status_code"])

            // The inner "handler" span for the failing sub-request should be marked errored (ok == false)
            // because the exception propagated through telemetryTrace.
            val failingHandlerSpan = spans
                .filter { it.name == "lightningserver.handler" }
                .firstOrNull { !it.ok }
            assertTrue(
                failingHandlerSpan != null,
                "Expected the inner 'lightningserver.handler' span for the failing sub-request to be marked errored",
            )
        }
    }
}
