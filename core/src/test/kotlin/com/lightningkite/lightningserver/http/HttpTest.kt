package com.lightningkite.lightningserver.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Unit tests for HTTP core classes in the http package.
 *
 * These tests cover the basic functionality of HttpStatus, HttpHeaders, HttpHeaderValue,
 * PathSegments, QueryParameters, and related parsing utilities.
 */
class HttpTest {

    // HttpStatus Tests

    @Test
    fun `HttpStatus success property works correctly`() {
        assertTrue(HttpStatus.OK.success)
        assertTrue(HttpStatus.Created.success)
        assertTrue(HttpStatus.NoContent.success)

        assertFalse(HttpStatus.BadRequest.success)
        assertFalse(HttpStatus.NotFound.success)
        assertFalse(HttpStatus.InternalServerError.success)
        assertFalse(HttpStatus.Continue.success)
        assertFalse(HttpStatus.Found.success)
    }

    @Test
    fun `HttpStatus toString includes description`() {
        assertEquals("200 OK", HttpStatus.OK.toString())
        assertEquals("404 Not Found", HttpStatus.NotFound.toString())
        assertEquals("500 Internal Server Error", HttpStatus.InternalServerError.toString())
    }

    @Test
    fun `HttpStatus custom code works`() {
        val custom = HttpStatus(418)
        assertEquals(418, custom.code)
        assertFalse(custom.success)
    }

    // HttpHeaders Tests

    @Test
    fun `HttpHeaders get is case insensitive`() {
        val headers = HttpHeaders("Content-Type" to "application/json")
        assertNotNull(headers["Content-Type"])
        assertNotNull(headers["content-type"])
        assertNotNull(headers["CONTENT-TYPE"])
        assertEquals("application/json", headers["content-type"]?.root)
    }

    @Test
    fun `HttpHeaders isEmpty and isNotEmpty work`() {
        assertTrue(HttpHeaders.EMPTY.isEmpty())
        assertFalse(HttpHeaders.EMPTY.isNotEmpty())

        val nonEmpty = HttpHeaders("X-Test" to "value")
        assertFalse(nonEmpty.isEmpty())
        assertTrue(nonEmpty.isNotEmpty())
    }

    @Test
    fun `HttpHeaders plus combines headers`() {
        val headers1 = HttpHeaders("X-First" to "1")
        val headers2 = HttpHeaders("X-Second" to "2")
        val combined = headers1 + headers2

        assertEquals("1", combined["X-First"]?.root)
        assertEquals("2", combined["X-Second"]?.root)
    }

    @Test
    fun `HttpHeaders plus preserves multiple values for same key`() {
        val headers1 = HttpHeaders("Accept" to "text/html")
        val headers2 = HttpHeaders("Accept" to "application/json")
        val combined = headers1 + headers2

        val acceptValues = combined.getMany("Accept")
        assertEquals(2, acceptValues.size)
    }

    @Test
    fun `HttpHeaders contentType parses correctly`() {
        val headers = HttpHeaders("Content-Type" to "application/json; charset=utf-8")
        val contentType = headers.contentType
        assertNotNull(contentType)
        assertEquals("application", contentType.type)
        assertEquals("json", contentType.subtype)
        assertEquals("utf-8", contentType.parameters["charset"])
    }

    @Test
    fun `HttpHeaders contentLength parses correctly`() {
        val headers = HttpHeaders("Content-Length" to "1234")
        assertEquals(1234L, headers.contentLength)

        val invalid = HttpHeaders("Content-Length" to "invalid")
        assertNull(invalid.contentLength)
    }

    @Test
    fun `HttpHeaders accept parses multiple media types`() {
        val headers = HttpHeaders {
            add("Accept", "text/html")
            add("Accept", "application/json")
        }
        val accept = headers.accept
        assertEquals(2, accept.size)
    }

    @Test
    fun `HttpHeaders Builder works`() {
        val headers = HttpHeaders {
            add("Content-Type", "application/json")
            add("X-Custom", "value")
        }

        assertEquals("application/json", headers["Content-Type"]?.root)
        assertEquals("value", headers["X-Custom"]?.root)
    }

    @Test
    fun `HttpHeaders Builder setCookie works`() {
        val headers = HttpHeaders {
            setCookie(
                name = "session",
                value = "abc123",
                path = "/",
                httpOnly = true,
                secure = true,
                sameSite = HttpHeaders.SameSite.Strict
            )
        }

        val cookies = headers.getMany(HttpHeader.SetCookie)
        assertEquals(1, cookies.size)
        val cookie = cookies.first()
        assertEquals("session", cookie.parameters["key"])
        assertEquals("abc123", cookie.parameters["value"])
        assertEquals("Strict", cookie.parameters["sameSite"])
    }

    @Test
    fun `HttpHeaders copy modifies headers correctly`() {
        val original = HttpHeaders("X-Original" to "value")
        val modified = original.copy {
            add("X-Added", "new")
        }

        assertEquals("value", modified["X-Original"]?.root)
        assertEquals("new", modified["X-Added"]?.root)
    }

    // HttpHeaderValue Tests

    @Test
    fun `HttpHeaderValue parses standard header correctly`() {
        val value = HttpHeaderValue.parse("Content-Type", "text/html; charset=utf-8")
        assertEquals("text/html", value.root)
        assertEquals("utf-8", value.parameters["charset"])
    }

    @Test
    fun `HttpHeaderValue parses header without parameters`() {
        val value = HttpHeaderValue.parse("Content-Type", "application/json")
        assertEquals("application/json", value.root)
        assertTrue(value.parameters.isEmpty())
    }

    @Test
    fun `HttpHeaderValue parses cookies correctly`() {
        val value = HttpHeaderValue.parse("Cookie", "session=abc123; user=john")
        assertEquals("", value.root)
        assertEquals("abc123", value.parameters["session"])
        assertEquals("john", value.parameters["user"])
    }

    @Test
    fun `HttpHeaderValue toHttpString works correctly`() {
        val value = HttpHeaderValue("text/html", mapOf("charset" to "utf-8"))
        assertEquals("text/html; charset=utf-8", value.toHttpString())
    }

    @Test
    fun `HttpHeaderValue toHttpString handles empty parameters`() {
        val value = HttpHeaderValue("text/html", emptyMap())
        assertEquals("text/html", value.toHttpString())
    }

    @Test
    fun `HttpHeaderValue toHttpString handles cookie format`() {
        val value = HttpHeaderValue("", mapOf("session" to "abc123", "httpOnly" to ""))
        val result = value.toHttpString()
        assertTrue(result.contains("session=abc123"))
        assertTrue(result.contains("httpOnly"))
    }

    // PathSegments Tests

    @Test
    fun `PathSegments parse handles basic path`() {
        val segments = PathSegments.parse("/api/users/123")
        assertEquals(listOf("api", "users", "123"), segments.segments)
    }

    @Test
    fun `PathSegments parse strips leading slash`() {
        val segments = PathSegments.parse("/api")
        assertEquals(listOf("api"), segments.segments)
    }

    @Test
    fun `PathSegments EMPTY works`() {
        assertEquals(0, PathSegments.EMPTY.segments.size)
    }

    // QueryParameters Tests

    @Test
    fun `QueryParameters parse handles basic query string`() {
        val params = QueryParameters.parse("filter=active&sort=name")
        assertEquals("active", params["filter"])
        assertEquals("name", params["sort"])
    }

    @Test
    fun `QueryParameters get returns null for missing key`() {
        val params = QueryParameters.parse("filter=active")
        assertNull(params["nonexistent"])
    }

    @Test
    fun `QueryParameters toString encodes parameters`() {
        val params = QueryParameters(listOf("name" to "john doe", "city" to "new york"))
        val result = params.toString()
        assertTrue(result.contains("name="))
        assertTrue(result.contains("city="))
    }

    @Test
    fun `QueryParameters EMPTY works`() {
        assertEquals(0, QueryParameters.EMPTY.entries.size)
        assertNull(QueryParameters.EMPTY["anything"])
    }

    @Test
    fun `QueryParameters supports multiple values for same key`() {
        val params = QueryParameters(listOf("tag" to "kotlin", "tag" to "server"))
        val allTags = params.entries.filter { it.first == "tag" }.map { it.second }
        assertEquals(2, allTags.size)
        assertTrue(allTags.contains("kotlin"))
        assertTrue(allTags.contains("server"))
    }

}
