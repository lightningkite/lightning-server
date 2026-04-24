// by Claude
package com.lightningkite.lightningserver.http

import kotlin.test.*

/**
 * Tests for HttpStatus value class.
 */
class HttpStatusTest {

    // ========== Success Property Tests ==========

    @Test
    fun `success returns true for 2xx codes`() {
        assertTrue(HttpStatus.OK.success)
        assertTrue(HttpStatus.Created.success)
        assertTrue(HttpStatus.Accepted.success)
        assertTrue(HttpStatus.NoContent.success)
        assertTrue(HttpStatus(200).success)
        assertTrue(HttpStatus(299).success)
    }

    @Test
    fun `success returns false for 1xx codes`() {
        assertFalse(HttpStatus.Continue.success)
        assertFalse(HttpStatus.SwitchingProtocols.success)
        assertFalse(HttpStatus(100).success)
    }

    @Test
    fun `success returns false for 3xx codes`() {
        assertFalse(HttpStatus.MultipleChoices.success)
        assertFalse(HttpStatus.MovedPermanently.success)
        assertFalse(HttpStatus.Found.success)
        assertFalse(HttpStatus.TemporaryRedirect.success)
    }

    @Test
    fun `success returns false for 4xx codes`() {
        assertFalse(HttpStatus.BadRequest.success)
        assertFalse(HttpStatus.Unauthorized.success)
        assertFalse(HttpStatus.Forbidden.success)
        assertFalse(HttpStatus.NotFound.success)
    }

    @Test
    fun `success returns false for 5xx codes`() {
        assertFalse(HttpStatus.InternalServerError.success)
        assertFalse(HttpStatus.BadGateway.success)
        assertFalse(HttpStatus.ServiceUnavailable.success)
    }

    // ========== Status Code Tests ==========

    @Test
    fun `status code values are correct`() {
        assertEquals(100, HttpStatus.Continue.code)
        assertEquals(101, HttpStatus.SwitchingProtocols.code)
        assertEquals(200, HttpStatus.OK.code)
        assertEquals(201, HttpStatus.Created.code)
        assertEquals(204, HttpStatus.NoContent.code)
        assertEquals(301, HttpStatus.MovedPermanently.code)
        assertEquals(302, HttpStatus.Found.code)
        assertEquals(307, HttpStatus.TemporaryRedirect.code)
        assertEquals(308, HttpStatus.PermanentRedirect.code)
        assertEquals(400, HttpStatus.BadRequest.code)
        assertEquals(401, HttpStatus.Unauthorized.code)
        assertEquals(403, HttpStatus.Forbidden.code)
        assertEquals(404, HttpStatus.NotFound.code)
        assertEquals(405, HttpStatus.MethodNotAllowed.code)
        assertEquals(409, HttpStatus.Conflict.code)
        assertEquals(429, HttpStatus.TooManyRequests.code)
        assertEquals(500, HttpStatus.InternalServerError.code)
        assertEquals(502, HttpStatus.BadGateway.code)
        assertEquals(503, HttpStatus.ServiceUnavailable.code)
    }

    // ========== toString Tests ==========

    @Test
    fun `toString returns code with description`() {
        assertEquals("200 OK", HttpStatus.OK.toString())
        assertEquals("404 Not Found", HttpStatus.NotFound.toString())
        assertEquals("500 Internal Server Error", HttpStatus.InternalServerError.toString())
    }

    @Test
    fun `toString for known codes includes description`() {
        assertTrue(HttpStatus.OK.toString().contains("OK"))
        assertTrue(HttpStatus.Created.toString().contains("Created"))
        assertTrue(HttpStatus.BadRequest.toString().contains("Bad Request"))
    }

    @Test
    fun `toString for unknown code returns just code`() {
        val customStatus = HttpStatus(418)
        assertEquals("418", customStatus.toString())
    }

    // ========== Custom Status Tests ==========

    @Test
    fun `custom status can be created`() {
        val teapot = HttpStatus(418)
        assertEquals(418, teapot.code)
        assertFalse(teapot.success)
    }

    // ========== Equality Tests ==========

    @Test
    fun `same status codes are equal`() {
        assertEquals(HttpStatus.OK, HttpStatus(200))
        assertEquals(HttpStatus.NotFound, HttpStatus(404))
    }

    @Test
    fun `different status codes are not equal`() {
        assertFalse(HttpStatus.OK == HttpStatus.Created)
        assertFalse(HttpStatus.NotFound == HttpStatus.BadRequest)
    }

    // ========== Strings Map Tests ==========

    @Test
    fun `strings map contains all common codes`() {
        assertTrue(HttpStatus.strings.containsKey(200))
        assertTrue(HttpStatus.strings.containsKey(404))
        assertTrue(HttpStatus.strings.containsKey(500))
        assertEquals("OK", HttpStatus.strings[200])
        assertEquals("Not Found", HttpStatus.strings[404])
    }
}
