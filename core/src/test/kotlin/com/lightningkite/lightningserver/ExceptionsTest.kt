// by Claude
package com.lightningkite.lightningserver

import com.lightningkite.lightningserver.http.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for HTTP exception classes.
 */
class ExceptionsTest {

    // ========== HttpStatusException Tests ==========

    @Test
    fun `HttpStatusException basic creation`() {
        val exception = HttpStatusException(
            status = HttpStatus.BadRequest,
            detail = "validation-error",
            message = "Invalid input",
            data = "field: name"
        )

        assertEquals(HttpStatus.BadRequest, exception.status)
        assertEquals("validation-error", exception.detail)
        assertEquals("Invalid input", exception.message)
        assertEquals("field: name", exception.data)
    }

    @Test
    fun `HttpStatusException default values`() {
        val exception = HttpStatusException(HttpStatus.InternalServerError)

        assertEquals(HttpStatus.InternalServerError, exception.status)
        assertEquals("", exception.detail)
        assertEquals("", exception.message)
        assertEquals("", exception.data)
    }

    @Test
    fun `HttpStatusException with cause`() {
        val cause = RuntimeException("Original error")
        val exception = HttpStatusException(
            status = HttpStatus.BadGateway,
            cause = cause
        )

        assertEquals(cause, exception.cause)
    }

    @Test
    fun `HttpStatusException from LSError`() {
        val lsError = LSError(
            http = 422,
            detail = "unprocessable",
            message = "Cannot process request",
            data = "reason: invalid state"
        )

        val exception = HttpStatusException(lsError)

        assertEquals(HttpStatus(422), exception.status)
        assertEquals("unprocessable", exception.detail)
        assertEquals("Cannot process request", exception.message)
        assertEquals("reason: invalid state", exception.data)
    }

    @Test
    fun `HttpStatusException toLSError conversion`() {
        val exception = HttpStatusException(
            status = HttpStatus(418),
            detail = "teapot",
            message = "I'm a teapot",
            data = "brew: coffee"
        )

        val lsError = exception.toLSError()

        assertEquals(418, lsError.http)
        assertEquals("teapot", lsError.detail)
        assertEquals("I'm a teapot", lsError.message)
        assertEquals("brew: coffee", lsError.data)
    }

    // ========== LSError.toException Tests ==========

    @Test
    fun `LSError toException conversion`() {
        val lsError = LSError(
            http = 409,
            detail = "conflict",
            message = "Resource already exists",
            data = "id: 123"
        )

        val exception = lsError.toException()

        assertEquals(HttpStatus(409), exception.status)
        assertEquals("conflict", exception.detail)
        assertEquals("Resource already exists", exception.message)
        assertEquals("id: 123", exception.data)
    }

    @Test
    fun `LSError toException with overrides`() {
        val lsError = LSError(
            http = 404,
            detail = "not-found",
            message = "Original message",
            data = "Original data"
        )

        val exception = lsError.toException(
            message = "Custom message",
            data = "Custom data"
        )

        assertEquals(HttpStatus.NotFound, exception.status)
        assertEquals("not-found", exception.detail)
        assertEquals("Custom message", exception.message)
        assertEquals("Custom data", exception.data)
    }

    // ========== BadRequestException Tests ==========

    @Test
    fun `BadRequestException has correct status`() {
        val exception = BadRequestException(
            detail = "validation",
            message = "Invalid input"
        )

        assertEquals(HttpStatus.BadRequest, exception.status)
        assertEquals(400, exception.status.code)
    }

    @Test
    fun `BadRequestException helper function`() {
        val exception = BadRequestException("Something is wrong")

        assertEquals(HttpStatus.BadRequest, exception.status)
        assertEquals("Something is wrong", exception.message)
        assertEquals("", exception.detail)
    }

    // ========== UnauthorizedException Tests ==========

    @Test
    fun `UnauthorizedException has correct status`() {
        val exception = UnauthorizedException(
            detail = "invalid-token",
            message = "Token expired"
        )

        assertEquals(HttpStatus.Unauthorized, exception.status)
        assertEquals(401, exception.status.code)
    }

    @Test
    fun `UnauthorizedException helper function`() {
        val exception = UnauthorizedException("Please log in")

        assertEquals(HttpStatus.Unauthorized, exception.status)
        assertEquals("Please log in", exception.message)
        assertEquals("", exception.detail)
    }

    // ========== ForbiddenException Tests ==========

    @Test
    fun `ForbiddenException has correct status`() {
        val exception = ForbiddenException(
            detail = "access-denied",
            message = "You don't have permission"
        )

        assertEquals(HttpStatus.Forbidden, exception.status)
        assertEquals(403, exception.status.code)
    }

    @Test
    fun `ForbiddenException helper function`() {
        val exception = ForbiddenException("Access denied")

        assertEquals(HttpStatus.Forbidden, exception.status)
        assertEquals("Access denied", exception.message)
        assertEquals("", exception.detail)
    }

    // ========== NotFoundException Tests ==========

    @Test
    fun `NotFoundException has correct status`() {
        val exception = NotFoundException(
            detail = "resource-not-found",
            message = "User not found"
        )

        assertEquals(HttpStatus.NotFound, exception.status)
        assertEquals(404, exception.status.code)
    }

    @Test
    fun `NotFoundException helper function`() {
        val exception = NotFoundException("Resource missing")

        assertEquals(HttpStatus.NotFound, exception.status)
        assertEquals("Resource missing", exception.message)
        assertEquals("", exception.detail)
    }

}
