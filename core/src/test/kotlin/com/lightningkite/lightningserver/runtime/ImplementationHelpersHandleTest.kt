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

class ImplementationHelpersHandleTest {

    // Minimal test server definition
    object TestServer : ServerBuilder() {
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
                        headers = HttpHeaders.EMPTY,
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )
                // Should be NoContent with Access-Control-Allow-Methods including GET, POST, OPTIONS, HEAD
                assertEquals(HttpStatus.NoContent, resp.status)
                val allow = resp.headers[HttpHeader.AccessControlAllowMethods]?.root ?: ""
                // Methods may be comma-joined without spaces
                val methods = allow.split(',').toSet()
                println("Methods found: $methods")
                assertTrue(setOf("GET", "POST", "OPTIONS", "HEAD").all { it in methods })
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
    fun gzip_compression_when_accepted() {
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
                // Should have content-encoding: gzip and body compressed
                assertEquals("gzip", resp.headers[HttpHeader.ContentEncoding]?.root)
                val compressed = resp.body?.data?.bytes() ?: error("Expected body bytes")
                // Decompress using GZIPInputStream to verify payload
                val decompressed = GZIPInputStream(ByteArrayInputStream(compressed)).readBytes()
                assertEquals("pong", decompressed.toString(Charsets.UTF_8))
            }
        }
    }
}
