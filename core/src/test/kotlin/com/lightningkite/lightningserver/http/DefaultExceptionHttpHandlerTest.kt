// by Claude
package com.lightningkite.lightningserver.http

import com.lightningkite.lightningserver.*
import com.lightningkite.lightningserver.definition.GeneralServerSettings
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.definition.generalSettings
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.settings.set
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/**
 * Tests for DefaultExceptionHttpHandler behavior.
 */
class DefaultExceptionHttpHandlerTest {

    object TestServer : ServerBuilder() {
        val throwBadRequest = path.path("bad-request").get bind HttpHandler {
            throw BadRequestException(detail = "validation-error", message = "Invalid input")
        }

        val throwUnauthorized = path.path("unauthorized").get bind HttpHandler {
            throw UnauthorizedException("Please login")
        }

        val throwNotFound = path.path("not-found").get bind HttpHandler {
            throw NotFoundException("Resource not found")
        }

        val throwCustomStatus = path.path("custom-status").get bind HttpHandler {
            throw HttpStatusException(
                status = HttpStatus(418),
                detail = "teapot",
                message = "I'm a teapot",
                data = "brew-type: coffee"
            )
        }

        val throwGenericException = path.path("generic").get bind HttpHandler {
            throw RuntimeException("Something went wrong")
        }

        val throwNullPointer = path.path("npe").get bind HttpHandler {
            throw NullPointerException("Null reference")
        }

        init {
            registerBasicMediaTypeCoders()
        }
    }

    @Test
    fun `BadRequestException returns 400 status`() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings(debug = false) }
        ) {
            runBlocking {
                val response = TestServer.throwBadRequest.test()
                assertEquals(400, response.status.code)
            }
        }
    }

    @Test
    fun `UnauthorizedException returns 401 status`() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings(debug = false) }
        ) {
            runBlocking {
                val response = TestServer.throwUnauthorized.test()
                assertEquals(401, response.status.code)
            }
        }
    }

    @Test
    fun `NotFoundException returns 404 status`() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings(debug = false) }
        ) {
            runBlocking {
                val response = TestServer.throwNotFound.test()
                assertEquals(404, response.status.code)
            }
        }
    }

    @Test
    fun `Custom HttpStatusException returns specified status`() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings(debug = false) }
        ) {
            runBlocking {
                val response = TestServer.throwCustomStatus.test()
                assertEquals(418, response.status.code)
            }
        }
    }

    @Test
    fun `Generic exception returns 500 in production mode`() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings(debug = false) }
        ) {
            runBlocking {
                val response = TestServer.throwGenericException.test()
                assertEquals(500, response.status.code)

                // Should NOT contain stack trace in production
                val body = response.body?.text() ?: ""
                assertFalse(body.contains("at "), "Stack trace should not be in production response")
            }
        }
    }

    @Test
    fun `Generic exception returns 500 with stack trace in debug mode`() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings(debug = true) }
        ) {
            runBlocking {
                val response = TestServer.throwGenericException.test()
                assertEquals(500, response.status.code)

                // Should contain stack trace in debug mode
                val body = response.body?.text() ?: ""
                assertTrue(
                    body.contains("stackTrace") || body.contains("RuntimeException"),
                    "Debug mode should include exception details"
                )
            }
        }
    }

    @Test
    fun `Exception detail preserved in response`() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings(debug = false) }
        ) {
            runBlocking {
                val response = TestServer.throwBadRequest.test()
                val body = response.body?.text() ?: ""
                assertTrue(body.contains("validation-error"), "Detail should be in response")
            }
        }
    }

    @Test
    fun `Exception message preserved in response`() {
        TestServer.test(
            settings = { generalSettings set GeneralServerSettings(debug = false) }
        ) {
            runBlocking {
                val response = TestServer.throwBadRequest.test()
                val body = response.body?.text() ?: ""
                assertTrue(body.contains("Invalid input"), "Message should be in response")
            }
        }
    }
}
