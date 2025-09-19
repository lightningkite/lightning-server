package com.lightningkite.lightningserver.runtime

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.loggingSettings
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.PathSpec0
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.LoggingSettings
import io.github.oshai.kotlinlogging.Level
import jdk.jfr.internal.LogLevel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlin.test.*
import com.lightningkite.MediaType
import com.lightningkite.services.data.TypedData
import kotlinx.io.writeString

class ImplementationHelpersHandleTest {

    // Minimal test server definition
    object TestServer : ServerBuilder() {
        // Install permissive CORS for OPTIONS tests
        val cors = com.lightningkite.lightningserver.cors.CorsSettings(
            limitToDomains = listOf("example.com"),
            limitToMethods = null
        )
        init {
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

        // Large plain text at /big for Range tests
        val bigGet = path.path("big").get bind HttpHandler<PathSpec0> {
            HttpResponse.plainText("z".repeat(10_000))
        }

        // Partial content response at /partial
        val partialGet = path.path("partial").get bind HttpHandler<PathSpec0> {
            HttpResponse.plainText("y".repeat(5_000), status = HttpStatus.PartialContent)
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
                assertEquals("pong", resp.body!!.text())
            }
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
                            set(HttpHeader.Origin, "example.com")
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
                            set(HttpHeader.AcceptEncoding, "gzip")
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
                            set(HttpHeader.AcceptEncoding, "gzip")
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
                val decompressed = GZIPInputStream(ByteArrayInputStream(compressed)).readBytes().toString(Charsets.UTF_8)
                assertEquals("x".repeat(100_000), decompressed)
            }
        }
    }

    @Test
    fun compression_skips_on_range_and_partial_content() {
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
                // a) Range header present: skip compression
                val rangeResp = serverRuntime.handle(
                    HttpRequest(
                        path = RawHttpEndpoint(asString = "/big", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders {
                            set(HttpHeader.AcceptEncoding, "gzip")
                            set(HttpHeader.Range, "bytes=0-99")
                        },
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                assertNull(rangeResp.headers[HttpHeader.ContentEncoding])

                // b) Partial Content status: skip compression
                val partialResp = serverRuntime.handle(
                    HttpRequest(
                        path = RawHttpEndpoint(asString = "/partial", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders {
                            set(HttpHeader.AcceptEncoding, "gzip")
                        },
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                assertEquals(HttpStatus.PartialContent, partialResp.status)
                assertNull(partialResp.headers[HttpHeader.ContentEncoding])
            }
        }
    }
}
