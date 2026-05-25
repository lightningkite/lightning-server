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
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Database
import com.lightningkite.services.otel.OpenTelemetrySettings
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
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

    /** Test-local in-memory OTEL plumbing; the equivalent helper in :core is internal. */
    private object Memory {
        @Volatile
        private var _latest: InMemorySpanExporter = InMemorySpanExporter.create()
        val latest: InMemorySpanExporter get() = _latest

        init {
            OpenTelemetrySettings.register("memory") { _, _, _ ->
                val exporter = InMemorySpanExporter.create()
                _latest = exporter
                OpenTelemetrySdk.builder()
                    .setTracerProvider(
                        SdkTracerProvider.builder()
                            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                            .build()
                    )
                    .build()
            }
        }

        fun finishedSpans(): List<SpanData> = _latest.finishedSpanItems.toList()
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
        TestServer.test(
            settings = {
                Memory  // ensure "memory" URL scheme is registered
                telemetrySettings.set(OpenTelemetrySettings(url = "memory"))
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

            val spans = Memory.finishedSpans()

            val okSpan = spans.singleOrNull { it.name == "GET /ok" }
                ?: fail("Expected per-sub-request span 'GET /ok'. Got: ${spans.map { it.name }}")
            assertEquals("GET", okSpan.attributes.asMap().entries.first { it.key.key == "http.method" }.value)
            assertEquals("/ok", okSpan.attributes.asMap().entries.first { it.key.key == "http.route" }.value)
            assertEquals("/ok", okSpan.attributes.asMap().entries.first { it.key.key == "http.target" }.value)
            assertEquals(200L, okSpan.attributes.asMap().entries.first { it.key.key == "http.status_code" }.value)

            val missingSpan = spans.singleOrNull { it.name == "GET /missing" }
                ?: fail("Expected per-sub-request span 'GET /missing'. Got: ${spans.map { it.name }}")
            assertEquals(
                404L,
                missingSpan.attributes.asMap().entries.first { it.key.key == "http.status_code" }.value,
                "Sub-request that threw NotFoundException should record http.status_code = 404",
            )

            // The bulk request itself still produces its own root span and reports success
            // because the endpoint always returns a 200 with per-sub-request results in the body.
            val bulkRoot = spans.singleOrNull { it.name == "POST /meta/bulk" }
                ?: fail("Expected root span 'POST /meta/bulk'. Got: ${spans.map { it.name }}")
            assertEquals(200L, bulkRoot.attributes.asMap().entries.first { it.key.key == "http.status_code" }.value)

            // The inner "handler" span for the failing sub-request gets ERROR status from the
            // SpanBuilder.use{} extension because the exception propagated through it.
            val failingHandlerSpan = spans
                .filter { it.name == "handler" }
                .firstOrNull { it.status.statusCode == StatusCode.ERROR }
            assertTrue(
                failingHandlerSpan != null,
                "Expected the inner 'handler' span for the failing sub-request to be marked ERROR",
            )
        }
    }
}
