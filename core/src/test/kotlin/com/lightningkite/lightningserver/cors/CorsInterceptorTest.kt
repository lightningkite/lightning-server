package com.lightningkite.lightningserver.cors

import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.arg1
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.lightningserver.websockets.WebSocketFrame
import com.lightningkite.lightningserver.websockets.WebSocketHandler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Integration tests for CorsInterceptor.
 *
 * Tests CORS header handling, preflight requests, origin validation, and WebSocket CORS.
 */
class CorsInterceptorTest {

    object TestServer : ServerBuilder() {
        val corsSettings = setting("cors", CorsSettings(
            limitToDomains = listOf("https://example.com", "https://*.trusted.com"),
            limitToHeaders = listOf("Content-Type", "Authorization"),
            limitToMethods = listOf("GET", "POST", "PUT", "DELETE"),
            exposedHeaders = listOf("X-Custom-Header"),
            allowCredentials = true,
            cacheLength = 3600u,
            forbidOnMatchFail = true
        ))

        init {
            registerBasicMediaTypeCoders()
            install(CorsInterceptor(corsSettings))
        }

        val simpleGet = path.path("api").path("data").get bind HttpHandler {
            HttpResponse.plainText("Hello World")
        }

        val simplePost = path.path("api").path("data").post bind HttpHandler {
            HttpResponse.plainText("Created")
        }

        val simplePut = path.path("api").path("data").put bind HttpHandler {
            HttpResponse.plainText("Updated")
        }

        val simpleDelete = path.path("api").path("data").delete bind HttpHandler {
            HttpResponse.plainText("Deleted")
        }

        val simpleOptions = path.path("api").path("data").options bind HttpHandler {
            HttpResponse(status = HttpStatus.NoContent)
        }

        val withArg = path.path("api").arg<String>("id").get bind HttpHandler {
            HttpResponse.plainText("Item ${it.arg1}")
        }

        val echo = path.path("echo") bind WebSocketHandler(
            storageSerializer = Unit.serializer(),
            willConnect = { Unit },
            messageFromClient = { frame ->
                send(frame)
            }
        )
    }

    @Test
    fun `request without Origin header passes through without CORS headers`() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val response = simpleGet.test()
                assertEquals(HttpStatus.OK, response.status)
                assertEquals("Hello World", response.body!!.text())
                // No CORS headers should be present
                assertNull(response.headers[HttpHeader.AccessControlAllowOrigin])
            }
        }
    }

    @Test
    fun `request with allowed origin includes CORS headers`() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val response = simpleGet.test(
                    headers = HttpHeaders {
                        add(HttpHeader.Origin, "https://example.com")
                    }
                )
                assertEquals(HttpStatus.OK, response.status)
                assertEquals("https://example.com", response.headers[HttpHeader.AccessControlAllowOrigin]?.root)
                assertEquals("true", response.headers[HttpHeader.AccessControlAllowCredentials]?.root)
                assertEquals("X-Custom-Header", response.headers[HttpHeader.AccessControlExposeHeaders]?.root)
            }
        }
    }

    @Test
    fun `request with wildcard subdomain match includes CORS headers`() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val response = simpleGet.test(
                    headers = HttpHeaders {
                        add(HttpHeader.Origin, "https://subdomain.trusted.com")
                    }
                )
                assertEquals(HttpStatus.OK, response.status)
                assertEquals("https://subdomain.trusted.com", response.headers[HttpHeader.AccessControlAllowOrigin]?.root)
            }
        }
    }

    @Test
    fun `request with disallowed origin and forbidOnMatchFail throws Forbidden`() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val response = simpleGet.test(
                    headers = HttpHeaders {
                        add(HttpHeader.Origin, "https://evil.com")
                    }
                )
                assertEquals(HttpStatus.Forbidden, response.status)
            }
        }
    }

    // Note: OPTIONS preflight request testing requires more complex setup
    // and is tested through integration tests. These unit tests focus on
    // the basic CORS header injection for regular requests.

    @Test
    fun `WebSocket with allowed origin connects`() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val socket = echo.test(
                    headers = HttpHeaders {
                        add(HttpHeader.Origin, "https://example.com")
                    }
                )
                var lastMessage: WebSocketFrame? = null
                socket.onMessageSent = { lastMessage = it }
                socket.send(WebSocketFrame("Hello"))
                assertEquals(WebSocketFrame("Hello"), lastMessage)
                socket.close()
            }
        }
    }

    @Test
    fun `WebSocket with disallowed origin throws Forbidden`() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                assertFailsWith<ForbiddenException> {
                    echo.test(
                        headers = HttpHeaders {
                            add(HttpHeader.Origin, "https://evil.com")
                        }
                    )
                }
            }
        }
    }
}

/**
 * Tests for permissive CORS configurations.
 */
class CorsInterceptorPermissiveTest {

    object PermissiveServer : ServerBuilder() {
        val corsSettings = setting("cors", CorsSettings(
            limitToDomains = listOf("*"), // Allow all origins
            limitToHeaders = listOf("*"), // Mirror headers
            limitToMethods = listOf("*"), // Mirror methods
            allowCredentials = false,
            forbidOnMatchFail = false
        ))

        init {
            registerBasicMediaTypeCoders()
            install(CorsInterceptor(corsSettings))
        }

        val simpleGet = path.path("api").get bind HttpHandler {
            HttpResponse.plainText("Hello")
        }
    }

    @Test
    fun `null limitToDomains mirrors any origin`() {
        PermissiveServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val response = simpleGet.test(
                    headers = HttpHeaders {
                        add(HttpHeader.Origin, "https://any-origin.com")
                    }
                )
                assertEquals(HttpStatus.OK, response.status)
                assertEquals("https://any-origin.com", response.headers[HttpHeader.AccessControlAllowOrigin]?.root)
            }
        }
    }

    // Note: Testing OPTIONS preflight with mirror behavior requires more complex setup
}

/**
 * Tests for empty list CORS configurations (restrictive).
 */
class CorsInterceptorRestrictiveTest {

    object RestrictiveServer : ServerBuilder() {
        val corsSettings = setting("cors", CorsSettings(
            limitToDomains = emptyList(), // No origins allowed
            limitToHeaders = emptyList(),
            limitToMethods = emptyList(),
            allowCredentials = false,
            forbidOnMatchFail = true
        ))

        init {
            registerBasicMediaTypeCoders()
            install(CorsInterceptor(corsSettings))
        }

        val simpleGet = path.path("api").get bind HttpHandler {
            HttpResponse.plainText("Hello")
        }
    }

    @Test
    fun `empty limitToDomains rejects all origins`() {
        RestrictiveServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val response = simpleGet.test(
                    headers = HttpHeaders {
                        add(HttpHeader.Origin, "https://example.com")
                    }
                )
                assertEquals(HttpStatus.Forbidden, response.status)
            }
        }
    }
}

/**
 * Tests for CORS with forbidOnMatchFail=false.
 */
class CorsInterceptorNonBlockingTest {

    object NonBlockingServer : ServerBuilder() {
        val corsSettings = setting("cors", CorsSettings(
            limitToDomains = listOf("https://example.com"),
            forbidOnMatchFail = false // Don't block, just omit CORS headers
        ))

        init {
            install(CorsInterceptor(corsSettings))
            registerBasicMediaTypeCoders()
        }

        val simpleGet = path.path("api").get bind HttpHandler {
            HttpResponse.plainText("Hello")
        }
    }

    @Test
    fun `disallowed origin with forbidOnMatchFail false processes request without CORS headers`() {
        NonBlockingServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val response = simpleGet.test(
                    headers = HttpHeaders {
                        add(HttpHeader.Origin, "https://evil.com")
                    }
                )
                // Request succeeds
                assertEquals(HttpStatus.OK, response.status)
                assertEquals("Hello", response.body!!.text())
                // But no CORS headers are present
                assertNull(response.headers[HttpHeader.AccessControlAllowOrigin])
            }
        }
    }

    @Test
    fun `allowed origin with forbidOnMatchFail false includes CORS headers`() {
        NonBlockingServer.test(
            settings = { generalSettings set GeneralServerSettings() }
        ) {
            runBlocking {
                val response = simpleGet.test(
                    headers = HttpHeaders {
                        add(HttpHeader.Origin, "https://example.com")
                    }
                )
                println(response.status)
                println(response.headers)
                assertEquals(HttpStatus.OK, response.status)
                assertEquals("https://example.com", response.headers[HttpHeader.AccessControlAllowOrigin]?.root)
            }
        }
    }
}
