package com.lightningkite.lightningserver.engine.conformance

import com.lightningkite.lightningserver.cors.CorsInterceptor
import com.lightningkite.lightningserver.cors.CorsSettings
import com.lightningkite.lightningserver.definition.ServerDefinition
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.HttpHandler
import com.lightningkite.lightningserver.http.HttpResponse
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.SecurityHeadersInterceptor
import com.lightningkite.lightningserver.http.get
import com.lightningkite.lightningserver.http.post
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.serialization.takeUnlessEmpty
import com.lightningkite.lightningserver.settings.ServerSettings
import kotlinx.coroutines.delay
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration as JDuration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Engine-agnostic HTTP conformance suite shared by every real-socket engine (Ktor, JDK, Netty).
 *
 * The four HTTP engines each hand-roll request/response translation, so a behavior that is correct
 * in one engine can silently regress in another. This suite encodes the cross-cutting contract from
 * the repository's `expectations.md` (security headers, HEAD fallback, trailing-slash redirect, CORS)
 * as a single set of assertions run against each engine over a real loopback socket.
 *
 * ## How to add an engine
 * Subclass this in the engine's `test` source set and implement [startEngine]: bind the engine to the
 * given port, map [maxBodySize] onto the engine's own body-cap setting, and return a [RunningEngine]
 * whose [RunningEngine.close] stops it. Every `@Test` here then runs against that engine automatically.
 *
 * Behaviors that are NOT engine-level and are therefore covered elsewhere:
 * - HSTS presence: only emitted over https; a loopback test speaks http, so this suite asserts its
 *   ABSENCE and the https branch is covered by SecurityHeadersInterceptor's core unit test.
 * - Range / Accept-Ranges / Accept-Post / Accept-Patch / the `Allow` header: see
 *   [options_preflight_returns_allowed_methods] — these `expectations.md` items are not implemented
 *   at the engine level today (OPTIONS is handled purely as CORS preflight).
 */
public abstract class EngineHttpConformanceSuite {

    /** Body-size cap the engine must enforce; small so the 413 test stays cheap. */
    protected val maxBodySize: Long = 1024L

    /** The origin the test server's CORS config allows; used by the CORS assertions. */
    protected val allowedOrigin: String = "https://allowed.example.com"

    /** A handle to a started engine bound to a concrete port. */
    public interface RunningEngine : AutoCloseable {
        public val port: Int
    }

    /**
     * Start THIS engine bound to [port], enforcing [maxBodySize] bytes on request bodies, serving
     * [conformanceDefinition]. Must return only once the engine is accepting connections.
     */
    protected abstract fun startEngine(port: Int, maxBodySize: Long): RunningEngine

    /** The single server definition every engine serves for these tests. */
    protected fun conformanceDefinition(): ServerDefinition = ConformanceServer.build()

    /**
     * Provide defaults for the settings that [conformanceDefinition] adds beyond the engine's own
     * required settings (currently just the CORS config). Call inside the engine's `settings.run { }`
     * block alongside the standard `generalSettings.useDefault()` etc.
     */
    protected fun ServerSettings.applyConformanceAppDefaults() {
        ConformanceServer.cors.useDefault()
    }

    /** Reserve a currently-free loopback port. */
    protected fun freePort(): Int = ServerSocket(0).use { (it.localSocketAddress as InetSocketAddress).port }

    /** Block until [port] accepts a TCP connection, so tests never race engine startup. */
    protected fun awaitBound(port: Int, timeoutMillis: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
                return
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        fail("Engine never bound to port $port within ${timeoutMillis}ms")
    }

    // --- Shared HTTP client (JDK built-in, so no engine test needs an extra HTTP client dependency) ---

    // Redirect.NEVER lets us observe the 307 trailing-slash redirect rather than transparently following it.
    private fun client(): HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(JDuration.ofSeconds(10))
        .build()

    private fun url(port: Int, path: String) = URI.create("http://127.0.0.1:$port$path")

    // Bound every request so a hung/broken engine fails this suite fast instead of blocking the whole
    // test task. Comfortably longer than the 500ms handler timeout the /slow route exercises.
    private fun request(port: Int, path: String) =
        HttpRequest.newBuilder(url(port, path)).timeout(JDuration.ofSeconds(20))

    // --- Tests ---

    @Test
    public fun nosniff_present_on_ok_response() {
        startEngine(freePort(), maxBodySize).use { running ->
            val resp = client().send(
                request(running.port, "/hello").GET().build(),
                BodyHandlers.ofString(),
            )
            assertEquals(200, resp.statusCode())
            assertEquals("hello", resp.body())
            assertEquals(
                "nosniff",
                resp.headers().firstValue("X-Content-Type-Options").orElse(null),
                "X-Content-Type-Options must be present on normal responses",
            )
        }
    }

    @Test
    public fun nosniff_present_on_error_response() {
        startEngine(freePort(), maxBodySize).use { running ->
            // Unmatched path -> 404 produced by the exception handler; it must still flow back out through
            // the outermost SecurityHeadersInterceptor.
            val resp = client().send(
                request(running.port, "/no-such-route").GET().build(),
                BodyHandlers.ofString(),
            )
            assertEquals(404, resp.statusCode())
            assertEquals(
                "nosniff",
                resp.headers().firstValue("X-Content-Type-Options").orElse(null),
                "X-Content-Type-Options must be present on error responses too",
            )
        }
    }

    @Test
    public fun head_falls_back_to_get_without_body() {
        startEngine(freePort(), maxBodySize).use { running ->
            val resp = client().send(
                request(running.port, "/hello").method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                BodyHandlers.ofString(),
            )
            // Core maps a successful HEAD-fallback to 204 No Content and strips the body.
            assertEquals(204, resp.statusCode(), "HEAD with no explicit handler should fall back to GET as 204")
            assertTrue(resp.body().isNullOrEmpty(), "HEAD response must have no body")
            assertEquals(
                "nosniff",
                resp.headers().firstValue("X-Content-Type-Options").orElse(null),
                "Security headers must still apply to HEAD responses",
            )
        }
    }

    @Test
    public fun trailing_slash_redirects_307() {
        startEngine(freePort(), maxBodySize).use { running ->
            // /hello is registered without a trailing slash; requesting /hello/ should 307 to /hello.
            val resp = client().send(
                request(running.port, "/hello/").GET().build(),
                BodyHandlers.ofString(),
            )
            assertEquals(307, resp.statusCode(), "A trailing-slash mismatch should produce a 307 redirect")
            val location = resp.headers().firstValue("Location").orElse("")
            assertTrue(location.endsWith("/hello"), "Redirect Location should point at /hello, was '$location'")
        }
    }

    @Test
    public fun cors_reflects_allowed_origin() {
        startEngine(freePort(), maxBodySize).use { running ->
            val resp = client().send(
                request(running.port, "/hello").GET()
                    .header("Origin", allowedOrigin).build(),
                BodyHandlers.ofString(),
            )
            assertEquals(200, resp.statusCode())
            assertEquals(
                allowedOrigin,
                resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null),
                "An allowed Origin must be reflected in Access-Control-Allow-Origin",
            )
        }
    }

    @Test
    public fun options_preflight_returns_allowed_methods() {
        startEngine(freePort(), maxBodySize).use { running ->
            // NOTE ON expectations.md: the OPTIONS contract the framework actually implements is CORS
            // preflight (via CorsInterceptor), not the static `Allow: OPTIONS, GET, HEAD, POST` /
            // `Accept-Post` / `Accept-Ranges` header block listed in expectations.md. Those latter headers
            // are a documented known-gap (see report). A preflight requires an Origin header; without one
            // the framework has no OPTIONS handler and returns 404.
            val resp = client().send(
                request(running.port, "/hello")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                    .header("Origin", allowedOrigin)
                    .header("Access-Control-Request-Method", "GET")
                    .build(),
                BodyHandlers.ofString(),
            )
            assertEquals(204, resp.statusCode(), "CORS preflight should be 204 No Content")
            assertEquals(
                allowedOrigin,
                resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null),
            )
            val allowMethods = resp.headers().firstValue("Access-Control-Allow-Methods").orElse("")
            assertTrue(
                allowMethods.contains("GET"),
                "Preflight Access-Control-Allow-Methods should advertise GET, was '$allowMethods'",
            )
        }
    }

    @Test
    public fun hsts_absent_over_http() {
        startEngine(freePort(), maxBodySize).use { running ->
            val resp = client().send(
                request(running.port, "/hello").GET().build(),
                BodyHandlers.ofString(),
            )
            // Per the HSTS spec the header is never emitted over plain http; the https branch is covered
            // by SecurityHeadersInterceptor's core unit test, not end-to-end here.
            assertNull(
                resp.headers().firstValue("Strict-Transport-Security").orElse(null),
                "Strict-Transport-Security must not be emitted over http",
            )
        }
    }

    @Test
    public fun handler_timeout_returns_503() {
        startEngine(freePort(), maxBodySize).use { running ->
            val resp = client().send(
                request(running.port, "/slow").GET().build(),
                BodyHandlers.ofString(),
            )
            assertEquals(
                HttpStatus.ServiceUnavailable.code,
                resp.statusCode(),
                "A handler that exceeds its timeout is a server-side condition, so it should yield 503 " +
                    "(enforced centrally in ServerRuntime.handle) — not 408, which means a slow client.",
            )
        }
    }

    @Test
    public fun oversized_body_returns_413() {
        startEngine(freePort(), maxBodySize).use { running ->
            val resp = client().send(
                request(running.port, "/echo")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(ByteArray((maxBodySize + 1).toInt()) { 'x'.code.toByte() }))
                    .build(),
                BodyHandlers.ofString(),
            )
            assertEquals(
                HttpStatus.PayloadTooLarge.code,
                resp.statusCode(),
                "A request body over the engine's cap should yield 413",
            )
        }
    }

    @Test
    public fun request_without_a_body_has_a_null_body() {
        startEngine(freePort(), maxBodySize).use { running ->
            // The JDK client sends `Content-Length: 0` for an empty POST.
            val declaredEmpty = client().send(
                request(running.port, "/body-kind").POST(HttpRequest.BodyPublishers.noBody()).build(),
                BodyHandlers.ofString(),
            )
            assertEquals("null", declaredEmpty.body(), "Content-Length: 0 means there is no body")

            // No Content-Length and no Transfer-Encoding at all, which no client library will send for a POST.
            val undeclared = java.net.Socket("127.0.0.1", running.port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().apply {
                    write("POST /body-kind HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                // Read until the body has arrived rather than until the server hangs up: not every
                // engine closes the connection just because it was asked to.
                val text = StringBuilder()
                val chunk = ByteArray(4096)
                try {
                    while (!text.contains("\r\n\r\n") || text.endsWith("\r\n\r\n")) {
                        val read = socket.getInputStream().read(chunk)
                        if (read < 0) break
                        text.append(String(chunk, 0, read))
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Fall through with whatever arrived; the assertions below report it.
                }
                text.toString()
            }
            assertTrue(undeclared.startsWith("HTTP/1.1 200"), "unexpected response: $undeclared")
            assertTrue(undeclared.trimEnd().endsWith("null"), "a request with no body framing has no body: $undeclared")
        }
    }

    @Test
    public fun request_with_a_body_has_a_body() {
        startEngine(freePort(), maxBodySize).use { running ->
            val declared = client().send(
                request(running.port, "/body-kind").POST(HttpRequest.BodyPublishers.ofString("abc")).build(),
                BodyHandlers.ofString(),
            )
            assertEquals("3 bytes", declared.body())

            // A stream publisher has no known length, so this body is sent chunked.
            val chunked = client().send(
                request(running.port, "/body-kind")
                    .POST(HttpRequest.BodyPublishers.ofInputStream { java.io.ByteArrayInputStream("abcde".toByteArray()) })
                    .build(),
                BodyHandlers.ofString(),
            )
            assertEquals("5 bytes", chunked.body())
        }
    }

    @Test
    public fun empty_body_of_undeclared_length_reads_as_no_body() {
        startEngine(freePort(), maxBodySize).use { running ->
            // A stream publisher has no known length, so each of these goes out chunked: the headers
            // promise a body, and only reading it shows whether anything is in it.
            fun send(content: String) = client().send(
                request(running.port, "/content-kind")
                    .POST(HttpRequest.BodyPublishers.ofInputStream { java.io.ByteArrayInputStream(content.toByteArray()) })
                    .build(),
                BodyHandlers.ofString(),
            ).body()

            assertEquals("none", send(""), "a chunked body with nothing in it is no body")
            assertEquals("5 bytes", send("abcde"), "checking a body for content must not lose any of it")
        }
    }

    @Test
    public fun oversized_body_of_undeclared_length_returns_413() {
        startEngine(freePort(), maxBodySize).use { running ->
            // A stream publisher has no known length, so the body goes out chunked: there is no
            // Content-Length to reject up front, and the limit can only be hit while a handler reads.
            val resp = client().send(
                request(running.port, "/echo")
                    .POST(HttpRequest.BodyPublishers.ofInputStream { java.io.ByteArrayInputStream(ByteArray(50_000)) })
                    .build(),
                BodyHandlers.ofString(),
            )
            assertEquals(
                HttpStatus.PayloadTooLarge.code,
                resp.statusCode(),
                "A body that passes the cap part-way through being read should still yield 413",
            )
        }
    }

    @Test
    public fun oversized_body_returns_413_even_if_the_handler_swallows_the_failure() {
        startEngine(freePort(), maxBodySize).use { running ->
            val resp = client().send(
                request(running.port, "/swallow")
                    .POST(HttpRequest.BodyPublishers.ofInputStream { java.io.ByteArrayInputStream(ByteArray(50_000)) })
                    .build(),
                BodyHandlers.ofString(),
            )
            assertEquals(
                HttpStatus.PayloadTooLarge.code,
                resp.statusCode(),
                "A handler that catches the failed read must not be able to answer an oversized upload with success; body was: ${resp.body()}",
            )
        }
    }

    /**
     * The single definition every engine serves. Kept private so the concrete engine tests reach it
     * only through [conformanceDefinition], and so its (effectively private) endpoint members don't trip
     * explicit-API checks.
     */
    private object ConformanceServer : ServerBuilder() {
        val cors = setting(
            "cors",
            CorsSettings(
                limitToDomains = listOf("https://allowed.example.com"),
                limitToMethods = listOf("GET", "HEAD", "POST"),
                // Non-matching origins simply get no CORS headers instead of 403, so the non-CORS tests
                // (which send no Origin) are unaffected.
                forbidOnMatchFail = false,
            ),
        )

        init {
            // Needed so the central timeout/error bodies can be serialized by the default exception handler.
            registerBasicMediaTypeCoders()
            // Installed first (outermost) so security headers apply to every response — including CORS-processed
            // and error responses — which the nosniff_* tests verify end-to-end through each engine.
            install(SecurityHeadersInterceptor())
            install(CorsInterceptor(cors))
        }

        val hello = path.path("hello").get bind HttpHandler<PathSpec0> {
            HttpResponse.plainText("hello")
        }
        val slow = path.path("slow").get bind HttpHandler<PathSpec0>(timeout = 500.milliseconds) {
            delay(5.seconds) // deliberately longer than the handler timeout above
            HttpResponse.plainText("done")
        }
        val echo = path.path("echo").post bind HttpHandler<PathSpec0> { request ->
            val size = request.body?.data?.bytes()?.size ?: 0
            HttpResponse.plainText("received $size")
        }

        // Reports whether the engine handed the handler a body at all.
        val bodyKind = path.path("body-kind").post bind HttpHandler<PathSpec0> { request ->
            HttpResponse.plainText(request.body?.let { "${it.data.bytes().size} bytes" } ?: "null")
        }

        // Reports what the body holds, counting an empty one as no body.
        val contentKind = path.path("content-kind").post bind HttpHandler<PathSpec0> { request ->
            HttpResponse.plainText(request.body?.takeUnlessEmpty()?.let { "${it.data.bytes().size} bytes" } ?: "none")
        }

        // Catches whatever reading the body throws and answers as though nothing happened.
        val swallow = path.path("swallow").post bind HttpHandler<PathSpec0> { request ->
            val size = try {
                request.body?.data?.bytes()?.size ?: 0
            } catch (_: Exception) {
                -1
            }
            HttpResponse.plainText("received $size")
        }
    }
}
