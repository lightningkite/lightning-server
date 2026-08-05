package com.lightningkite.lightningserver.telemetry

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.cors.CorsInterceptor
import com.lightningkite.lightningserver.cors.CorsSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.telemetrySettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.telemetry.TelemetryBackend
import io.opentelemetry.api.trace.SpanId
import io.opentelemetry.sdk.trace.data.SpanData
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Verifies that the shared `ServerRuntime.handle()` extension produces a root span named
 * "$METHOD $route" with standard http.* attributes, and that interceptor spans nest inside it.
 *
 * Because every engine (Ktor, Netty, JDK, AWS-serverless) routes HTTP requests through this same
 * extension function, validating it here also validates HTTP instrumentation for those engines.
 */
class HttpSpanTest {

    object TestServer : ServerBuilder() {
        val cors = CorsSettings(limitToDomains = listOf("example.com"))

        init {
            // Installed outermost (before CORS) so its span is the direct child of the route root.
            install(SecurityHeadersInterceptor())
            install(CorsInterceptor(setting("cors", cors)))
        }

        val getUser = path.path("users").arg<String>("id").get bind HttpHandler<PathSpec1<String>> {
            HttpResponse.plainText("user ${it.path.arg1}")
        }
    }

    @Test
    fun root_span_is_method_plus_route_with_interceptor_nested() {
        TestServer.test(
            settings = {
                InMemoryTelemetry  // ensure "memory" URL scheme is registered
                telemetrySettings.set(TelemetryBackend.Settings(url = "memory"))
            }
        ) {
            runBlocking {
                serverRuntime.handle(
                    HttpRequest<PathSpec>(
                        path = RawHttpEndpoint(asString = "/users/abc", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders { add(HttpHeader.Origin, "https://example.com") },
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
            }

            val spans = InMemoryTelemetry.finishedSpans()
            val root = spans.singleOrNull { it.parentSpanContext.spanId == SpanId.getInvalid() }
                ?: fail("Expected exactly one root span. Got: ${spans.map { it.name }}")

            assertEquals(
                "lightningserver.GET /users/{id}",
                root.name,
                "Root span name should be \"lightningserver.\$METHOD \$route-pattern\"",
            )
            assertEquals("GET", root.attributes.asMap().entries.first { it.key.key == "http.method" }.value)
            assertEquals(
                "/users/{id}",
                root.attributes.asMap().entries.first { it.key.key == "http.route" }.value,
            )
            assertEquals(
                "/users/abc",
                root.attributes.asMap().entries.first { it.key.key == "http.target" }.value,
            )
            assertEquals(200L, root.attributes.asMap().entries.first { it.key.key == "http.status_code" }.value)

            // This test server installs SecurityHeadersInterceptor outermost, so its span is the direct
            // child of the route root.
            val security = spans.singleOrNull { it.name == "lightningserver.SecurityHeaders" }
                ?: fail("Expected a SecurityHeaders interceptor span. Got: ${spans.map { it.name }}")
            assertEquals(
                root.spanContext.spanId,
                security.parentSpanContext.spanId,
                "SecurityHeaders interceptor span should be a child of the route root span",
            )

            // The CORS interceptor, installed after it, nests inside the SecurityHeaders span.
            val cors = spans.singleOrNull { it.name == "lightningserver.CORS" }
                ?: fail("Expected a CORS interceptor span. Got: ${spans.map { it.name }}")
            assertEquals(
                security.spanContext.spanId,
                cors.parentSpanContext.spanId,
                "CORS interceptor span should nest inside the SecurityHeaders span",
            )
        }
    }

    @Test
    fun unmatched_request_still_produces_root_span() {
        TestServer.test(
            settings = {
                InMemoryTelemetry  // ensure "memory" URL scheme is registered
                telemetrySettings.set(TelemetryBackend.Settings(url = "memory"))
            }
        ) {
            runBlocking {
                serverRuntime.handle(
                    HttpRequest<PathSpec>(
                        path = RawHttpEndpoint(asString = "/does/not/exist", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders.EMPTY,
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
            }

            val spans = InMemoryTelemetry.finishedSpans()
            val root = spans.firstOrNull { it.parentSpanContext.spanId == SpanId.getInvalid() }
                ?: fail("Expected a root span even for unmatched routes. Got: ${spans.map { it.name }}")

            // For unmatched paths the literal target is used as the route — what matters is a
            // single top-level HTTP span with the correct verb.
            assertTrue(
                root.name.startsWith("lightningserver.GET "),
                "Root span should start with \"lightningserver.GET \", was \"${root.name}\"",
            )
            assertNotNull(
                root.attributes.asMap().entries.firstOrNull { it.key.key == "http.method" }?.value,
                "Root span should carry http.method attribute",
            )
        }
    }
}
