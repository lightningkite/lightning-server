package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.HttpStatusException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.runtime.test.testBlocking
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.LoggingSettings
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.io.writeString
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ImplementationHelpersHandleTest {

    // Minimal test server definition
    object TestServer : ServerBuilder() {
        // Install permissive CORS for OPTIONS tests
        val cors = com.lightningkite.lightningserver.cors.CorsSettings(
            limitToDomains = listOf("example.com"),
            limitToMethods = listOf("*")
        )

        init {
            // Installed outermost so security headers apply to every response, including CORS-processed and
            // error responses (exercised by the security-header tests below).
            install(com.lightningkite.lightningserver.http.SecurityHeadersInterceptor())
            // Outside CORS so it also compresses responses the CORS layer produces (exercised by the gzip tests below).
            install(com.lightningkite.lightningserver.compression.GzipInterceptor())
            install(com.lightningkite.lightningserver.cors.CorsInterceptor(setting("cors", cors)))
        }

        // Simple GET /ping returns "pong"
        val root = path.get bind HttpHandler<PathSpec0> {
            HttpResponse.plainText("root")
        }

        // Simple GET /ping returns "pong"
        val getPing = path.path("ping").get bind HttpHandler<PathSpec0> {
            HttpResponse.plainText("pong")
        }

        // Simple POST /ping returns "posted"
        val postPing = path.path("ping").post bind HttpHandler<PathSpec0> {
            HttpResponse.plainText("posted")
        }

        // Only a GET at /slash/ (with trailing slash)
        val getSlashWithTrailing = path.path("slash").slash.get bind HttpHandler<PathSpec0> {
            HttpResponse.plainText("slash")
        }

        // Streaming large response at /bigstream
        val bigStream = path.path("bigstream").get bind HttpHandler<PathSpec0> {
            val content = "x".repeat(100_000)
            HttpResponse(
                body = TypedData.sink(mediaType = MediaType.Text.Plain) { sink ->
                    sink.writeString(content)
                }
            )
        }

        // Streaming large response backed by a blocking Data.Source at /bigsource
        val bigSource = path.path("bigsource").get bind HttpHandler<PathSpec0> {
            val content = "s".repeat(100_000)
            HttpResponse(
                body = TypedData.source(
                    source = kotlinx.io.Buffer().also { it.writeString(content) },
                    mediaType = MediaType.Text.Plain,
                ),
            )
        }

        // Large plain text at /big for Range tests
        val bigGet = path.path("big").get bind HttpHandler<PathSpec0> {
            HttpResponse.plainText("z".repeat(10_000))
        }

        // Partial content response at /partial
        val partialGet = path.path("partial").get bind HttpHandler<PathSpec0> {
            HttpResponse.plainText("y".repeat(5_000), status = HttpStatus.PartialContent)
        }

        // Handler that intentionally runs longer than its own short per-handler timeout.
        val slow = path.path("slow").get bind HttpHandler<PathSpec0>(timeout = 100.milliseconds) {
            delay(5.seconds)
            HttpResponse.plainText("done")
        }

        // Fast handler with the same short timeout to confirm normal completion is unaffected.
        val fast = path.path("fast").get bind HttpHandler<PathSpec0>(timeout = 100.milliseconds) {
            HttpResponse.plainText("quick")
        }

        // Always throws, to prove error responses still receive interceptor post-processing.
        val boom = path.path("boom").get bind HttpHandler<PathSpec0> {
            throw NotFoundException(detail = "boom", message = "Boom.")
        }

        init {
            registerBasicMediaTypeCoders()
        }
    }

    @Test
    fun normal_get_passthrough() {
        TestServer.test(
            settings = {
                loggingSettings.set(
                    LoggingSettings(
                        LoggingSettings.ContextSettings(
                            filePattern = null,
                            toConsole = true,
                            level = Level.DEBUG
                        )
                    )
                )
            }
        ) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest<PathSpec>(
                        path = RawHttpEndpoint(asString = "/ping", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders.EMPTY,
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                assertEquals(HttpStatus.OK, resp.status)
                assertNotNull(resp.body)
                assertEquals("pong", resp.body.text())
            }
        }
    }

    // Verifies the testBlocking variant: a suspend action body can call suspending APIs
    // (serverRuntime.handle, resp.body.text()) directly, with no inner runBlocking wrapper.
    @Test
    fun test_blocking_runs_suspend_body_without_run_blocking() {
        TestServer.testBlocking(settings = {}) {
            val resp = serverRuntime.handle(
                HttpRequest<PathSpec>(
                    path = RawHttpEndpoint(asString = "/ping", method = HttpMethod.GET),
                    queryParameters = QueryParameters.EMPTY,
                    headers = HttpHeaders.EMPTY,
                    domain = "example.com",
                    protocol = "https",
                    sourceIp = "local",
                )
            )
            assertEquals(HttpStatus.OK, resp.status)
            assertEquals("pong", resp.body!!.text())
        }
    }

    @Test
    fun head_translator_uses_get_and_strips_body() {
        TestServer.test(
            settings = {
                loggingSettings.set(
                    LoggingSettings(
                        LoggingSettings.ContextSettings(
                            filePattern = null,
                            toConsole = true,
                            level = Level.DEBUG
                        )
                    )
                )
            }
        ) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest(
                        path = RawHttpEndpoint(asString = "/ping", method = HttpMethod.HEAD),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders.EMPTY,
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                // On success, translation should set NoContent and remove body
                assertEquals(HttpStatus.NoContent, resp.status)
                assertNull(resp.body)
            }
        }
    }

    @Test
    fun options_lists_available_methods() {
        TestServer.test(
            settings = {
                loggingSettings.set(
                    LoggingSettings(
                        LoggingSettings.ContextSettings(
                            filePattern = null,
                            toConsole = true,
                            level = Level.DEBUG
                        )
                    )
                )
            }
        ) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest(
                        path = RawHttpEndpoint(asString = "/ping", method = HttpMethod.OPTIONS),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders {
                            add(HttpHeader.Origin, "example.com")
                        },
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                // Should be NoContent with Access-Control-Allow-Methods including GET, POST, HEAD
                assertEquals(HttpStatus.NoContent, resp.status)
                val allow = resp.headers[HttpHeader.AccessControlAllowMethods]?.root ?: ""
                // Methods may be comma-joined without spaces
                val methods = allow.split(',').toSet()
                println("Methods found: $methods")
                assertTrue(setOf("GET", "POST", "HEAD").all { it in methods })
            }
        }
    }

    @Test
    fun trailing_slash_redirects() {
        TestServer.test(
            settings = {
                loggingSettings.set(
                    LoggingSettings(
                        LoggingSettings.ContextSettings(
                            filePattern = null,
                            toConsole = true,
                            level = Level.DEBUG
                        )
                    )
                )
            }
        ) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest(
                        path = RawHttpEndpoint(asString = "/slash", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders.EMPTY,
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                // Expect a redirect with Location header pointing to alternate form
                assertEquals(HttpStatus.TemporaryRedirect, resp.status)
                val location = resp.headers[HttpHeader.Location]?.root
                assertEquals("/slash/", location)
            }
        }
    }

    @Test
    fun trailing_slash_no_redirect_when_correct() {
        // Test that a request WITH a trailing slash to an endpoint that requires trailing slash
        // does NOT redirect (would cause infinite loop if it did)
        TestServer.test(
            settings = {
                loggingSettings.set(
                    LoggingSettings(
                        LoggingSettings.ContextSettings(
                            filePattern = null,
                            toConsole = true,
                            level = Level.DEBUG
                        )
                    )
                )
            }
        ) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest(
                        path = RawHttpEndpoint(asString = "/slash/", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders.EMPTY,
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                // Should succeed directly, NOT redirect (which would cause infinite loop)
                assertEquals(HttpStatus.OK, resp.status, "Request to /slash/ should succeed directly")
                assertEquals("slash", resp.body?.text())
            }
        }
    }

    @Test
    fun trailing_slash_redirects_root() {
        TestServer.test(
            settings = {
                loggingSettings.set(
                    LoggingSettings(
                        LoggingSettings.ContextSettings(
                            filePattern = null,
                            toConsole = true,
                            level = Level.DEBUG
                        )
                    )
                )
            }
        ) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest(
                        path = RawHttpEndpoint(asString = "/", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders.EMPTY,
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                // Expect a redirect with Location header pointing to alternate form
                println(resp)
                assertNotEquals(HttpStatus.TemporaryRedirect, resp.status)
            }
        }
    }

    @Test
    fun trailing_slash_redirects_root2() {
        TestServer.test(
            settings = {
                loggingSettings.set(
                    LoggingSettings(
                        LoggingSettings.ContextSettings(
                            filePattern = null,
                            toConsole = true,
                            level = Level.DEBUG
                        )
                    )
                )
            }
        ) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest(
                        path = RawHttpEndpoint(asString = "", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders.EMPTY,
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                // Expect a redirect with Location header pointing to alternate form
                println(resp)
                assertNotEquals(HttpStatus.TemporaryRedirect, resp.status)
            }
        }
    }

    @Test
    fun path_segments_parse_preserves_trailing_slash() {
        // Verify PathSegments.parse correctly preserves trailing slashes
        val withTrailing = PathSegments.parse("/foo/")
        assertEquals(listOf("foo", ""), withTrailing.segments, "Trailing slash should be empty string segment")

        val withoutTrailing = PathSegments.parse("/foo")
        assertEquals(listOf("foo"), withoutTrailing.segments, "No trailing slash should have no empty segment")

        val rootOnly = PathSegments.parse("/")
        assertEquals(listOf(""), rootOnly.segments, "Root slash parses to single empty segment")

        val empty = PathSegments.parse("")
        assertEquals(listOf(""), empty.segments, "Empty string parses to single empty segment")
    }

    @Test
    fun handler_exceeding_its_timeout_returns_503() {
        // The timeout now lives in core: ServerRuntime.handle enforces HttpHandler.timeout and maps an
        // exceeded handler to 503 (a server-side condition — not 408, which means a slow client),
        // regardless of which engine runs it.
        TestServer.test(settings = {}) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest<PathSpec>(
                        path = RawHttpEndpoint(asString = "/slow", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders.EMPTY,
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                assertEquals(HttpStatus.ServiceUnavailable, resp.status)
            }
        }
    }

    @Test
    fun error_response_still_receives_cors_headers() {
        // Regression: a handler that throws must still get CORS headers. The exception is now
        // mapped to a response INSIDE the interceptor chain, so CORS post-processes it. Without
        // this, the browser masks every 4xx/5xx as a CORS failure and the real error (here, a
        // 404) is invisible to client JS.
        TestServer.test(settings = {}) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest<PathSpec>(
                        path = RawHttpEndpoint(asString = "/boom", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders { add(HttpHeader.Origin, "https://example.com") },
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                assertEquals(HttpStatus.NotFound, resp.status)
                assertEquals(
                    "https://example.com",
                    resp.headers[HttpHeader.AccessControlAllowOrigin]?.root,
                    "error responses must carry the CORS allow-origin header",
                )
            }
        }
    }

    // A minimal server whose second (innermost) interceptor always throws before calling its
    // continuation - simulating a rate limiter or auth interceptor rejecting a request. CORS is
    // installed first (outermost) so this proves outer interceptors still post-process a response
    // that resulted from an *interceptor's own* thrown exception, not just a handler's.
    object InterceptorFailureTestServer : ServerBuilder() {
        val cors = com.lightningkite.lightningserver.cors.CorsSettings(
            limitToDomains = listOf("example.com"),
            limitToMethods = listOf("*"),
        )

        init {
            install(com.lightningkite.lightningserver.cors.CorsInterceptor(setting("cors", cors)))
            install(HttpInterceptor { _, _ ->
                throw HttpStatusException(
                    status = HttpStatus.TooManyRequests,
                    detail = "boom-interceptor",
                    message = "Simulated interceptor failure.",
                )
            })
            registerBasicMediaTypeCoders()
        }
    }

    @Test
    fun error_thrown_by_interceptor_itself_still_receives_outer_post_processing() {
        // Regression: an interceptor that throws directly (not the handler) must still be
        // recovered close enough to the throw site that interceptors wrapping it - here, CORS -
        // see a normal response back from their continuation and still post-process it.
        InterceptorFailureTestServer.test(settings = {}) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest<PathSpec>(
                        path = RawHttpEndpoint(asString = "/anything", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders { add(HttpHeader.Origin, "https://example.com") },
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                assertEquals(HttpStatus.TooManyRequests, resp.status)
                assertEquals(
                    "https://example.com",
                    resp.headers[HttpHeader.AccessControlAllowOrigin]?.root,
                    "CORS (an outer interceptor) must still post-process a response produced by an inner interceptor's own thrown exception",
                )
            }
        }
    }

    @Test
    fun https_response_has_security_headers() {
        // This test server installs SecurityHeadersInterceptor: an https response must carry nosniff and HSTS.
        TestServer.test(settings = {}) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest<PathSpec>(
                        path = RawHttpEndpoint(asString = "/ping", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders.EMPTY,
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                assertEquals("nosniff", resp.headers[HttpHeader.XContentTypeOptions]?.root)
                assertEquals(
                    "max-age=31536000",
                    resp.headers[HttpHeader.StrictTransportSecurity]?.root,
                    "https responses must carry HSTS",
                )
            }
        }
    }

    @Test
    fun http_response_has_nosniff_but_not_hsts() {
        // HSTS must never be sent over plain http (per the HSTS spec), but nosniff still applies.
        TestServer.test(settings = {}) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest<PathSpec>(
                        path = RawHttpEndpoint(asString = "/ping", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders.EMPTY,
                        domain = "example.com",
                        protocol = "http",
                        sourceIp = "local",
                    )
                )
                assertEquals("nosniff", resp.headers[HttpHeader.XContentTypeOptions]?.root)
                assertNull(
                    resp.headers[HttpHeader.StrictTransportSecurity],
                    "plain http responses must NOT carry HSTS",
                )
            }
        }
    }

    @Test
    fun error_response_has_security_headers() {
        // Error responses are mapped inside the interceptor chain, so security headers apply to
        // them too.
        TestServer.test(settings = {}) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest<PathSpec>(
                        path = RawHttpEndpoint(asString = "/boom", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders.EMPTY,
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                assertEquals(HttpStatus.NotFound, resp.status)
                assertEquals("nosniff", resp.headers[HttpHeader.XContentTypeOptions]?.root)
                assertEquals(
                    "max-age=31536000",
                    resp.headers[HttpHeader.StrictTransportSecurity]?.root,
                    "error responses must carry security headers",
                )
            }
        }
    }

    @Test
    fun fast_handler_completes_within_its_timeout() {
        TestServer.test(settings = {}) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest<PathSpec>(
                        path = RawHttpEndpoint(asString = "/fast", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders.EMPTY,
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                assertEquals(HttpStatus.OK, resp.status)
                assertEquals("quick", resp.body?.text())
            }
        }
    }

    @Test
    fun gzip_skips_small_payloads() {
        TestServer.test(
            settings = {
                loggingSettings.set(
                    LoggingSettings(
                        LoggingSettings.ContextSettings(
                            filePattern = null,
                            toConsole = true,
                            level = Level.DEBUG
                        )
                    )
                )
            }
        ) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest(
                        path = RawHttpEndpoint(asString = "/ping", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders {
                            add(HttpHeader.AcceptEncoding, "gzip")
                        },
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                // Small payload should not be compressed even if gzip is accepted
                assertNull(resp.headers[HttpHeader.ContentEncoding])
                assertEquals("pong", resp.body?.text())
            }
        }
    }

    @Test
    fun gzip_applied_on_stream_sink() {
        TestServer.test(
            settings = {
                loggingSettings.set(
                    LoggingSettings(
                        LoggingSettings.ContextSettings(
                            filePattern = null,
                            toConsole = true,
                            level = Level.DEBUG
                        )
                    )
                )
            }
        ) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest(
                        path = RawHttpEndpoint(asString = "/bigstream", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders {
                            add(HttpHeader.AcceptEncoding, "gzip")
                        },
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                assertEquals(HttpStatus.OK, resp.status)
                // Should have content-encoding: gzip and body compressed
                assertEquals("gzip", resp.headers[HttpHeader.ContentEncoding]?.root)
                val compressed = resp.body?.data?.bytes() ?: error("Expected body bytes")
                val decompressed =
                    GZIPInputStream(ByteArrayInputStream(compressed)).readBytes().toString(Charsets.UTF_8)
                assertEquals("x".repeat(100_000), decompressed)
            }
        }
    }

    @Test
    fun gzip_applied_on_stream_source() {
        // The blocking Data.Source path must stream-compress (no full-body buffering) and still produce valid gzip.
        TestServer.test(settings = {}) {
            runBlocking {
                val resp = serverRuntime.handle(
                    HttpRequest(
                        path = RawHttpEndpoint(asString = "/bigsource", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders { add(HttpHeader.AcceptEncoding, "gzip") },
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                assertEquals(HttpStatus.OK, resp.status)
                assertEquals("gzip", resp.headers[HttpHeader.ContentEncoding]?.root)
                val compressed = resp.body?.data?.bytes() ?: error("Expected body bytes")
                val decompressed =
                    GZIPInputStream(ByteArrayInputStream(compressed)).readBytes().toString(Charsets.UTF_8)
                assertEquals("s".repeat(100_000), decompressed)
            }
        }
    }
}
