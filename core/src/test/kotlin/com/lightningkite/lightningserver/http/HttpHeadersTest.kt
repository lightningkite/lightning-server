// by Claude
package com.lightningkite.lightningserver.http

import kotlin.test.*

/**
 * Tests for HttpHeaders class.
 */
class HttpHeadersTest {

    // ========== Construction Tests ==========

    @Test
    fun `empty headers creation`() {
        val headers = HttpHeaders.EMPTY
        assertTrue(headers.isEmpty())
        assertFalse(headers.isNotEmpty())
    }

    @Test
    fun `headers from vararg pairs`() {
        val headers = HttpHeaders(
            "Content-Type" to "application/json",
            "Accept" to "text/html"
        )
        assertFalse(headers.isEmpty())
        assertTrue(headers.isNotEmpty())
    }

    @Test
    fun `headers from iterable`() {
        val pairs = listOf("X-Custom" to "value1", "X-Other" to "value2")
        val headers = HttpHeaders(pairs)
        assertEquals("value1", headers["X-Custom"]?.root)
        assertEquals("value2", headers["X-Other"]?.root)
    }

    @Test
    fun `headers from builder DSL`() {
        val headers = HttpHeaders {
            add("Content-Type", "application/json")
            add("Accept", "text/html")
        }
        assertEquals(2, headers.normalizedEntries.size)
    }

    // ========== Get Tests ==========

    @Test
    fun `get returns first value`() {
        val headers = HttpHeaders("X-Test" to "value1")
        assertEquals("value1", headers["X-Test"]?.root)
    }

    @Test
    fun `get is case insensitive`() {
        val headers = HttpHeaders("Content-Type" to "application/json")
        assertEquals("application/json", headers["content-type"]?.root)
        assertEquals("application/json", headers["CONTENT-TYPE"]?.root)
        assertEquals("application/json", headers["Content-Type"]?.root)
    }

    @Test
    fun `get returns null for missing header`() {
        val headers = HttpHeaders.EMPTY
        assertNull(headers["X-Missing"])
    }

    @Test
    fun `getMany returns all values`() {
        val headers = HttpHeaders {
            add("Accept", "text/html")
            add("Accept", "application/json")
        }
        val accepts = headers.getMany("Accept")
        assertEquals(2, accepts.size)
    }

    @Test
    fun `getMany returns empty list for missing header`() {
        val headers = HttpHeaders.EMPTY
        val result = headers.getMany("X-Missing")
        assertTrue(result.isEmpty())
    }

    // ========== Computed Properties Tests ==========

    @Test
    fun `contentType parses correctly`() {
        val headers = HttpHeaders("Content-Type" to "application/json")
        val contentType = headers.contentType
        assertNotNull(contentType)
        assertEquals("application", contentType.type)
        assertEquals("json", contentType.subtype)
    }

    @Test
    fun `contentType with parameters`() {
        val headers = HttpHeaders("Content-Type" to "text/html; charset=utf-8")
        val contentType = headers.contentType
        assertNotNull(contentType)
        assertEquals("text", contentType.type)
        assertEquals("html", contentType.subtype)
    }

    @Test
    fun `contentType returns null when missing`() {
        val headers = HttpHeaders.EMPTY
        assertNull(headers.contentType)
    }

    @Test
    fun `contentLength parses correctly`() {
        val headers = HttpHeaders("Content-Length" to "1234")
        assertEquals(1234L, headers.contentLength)
    }

    @Test
    fun `contentLength returns null when missing`() {
        val headers = HttpHeaders.EMPTY
        assertNull(headers.contentLength)
    }

    @Test
    fun `accept parses multiple values`() {
        val headers = HttpHeaders {
            add("Accept", "text/html")
            add("Accept", "application/json")
        }
        val accepts = headers.accept
        assertEquals(2, accepts.size)
    }

    // ========== Plus Operator Tests ==========

    @Test
    fun `plus combines headers`() {
        val headers1 = HttpHeaders("X-One" to "value1")
        val headers2 = HttpHeaders("X-Two" to "value2")
        val combined = headers1 + headers2
        assertEquals("value1", combined["X-One"]?.root)
        assertEquals("value2", combined["X-Two"]?.root)
    }

    @Test
    fun `plus merges same headers`() {
        val headers1 = HttpHeaders("Accept" to "text/html")
        val headers2 = HttpHeaders("Accept" to "application/json")
        val combined = headers1 + headers2
        assertEquals(2, combined.getMany("Accept").size)
    }

    // ========== Builder Tests ==========

    @Test
    fun `builder add string values`() {
        val headers = HttpHeaders.Builder()
            .apply {
                add("X-Test", "value")
            }
            .build()
        assertEquals("value", headers["X-Test"]?.root)
    }

    @Test
    fun `builder add multiple values for same key`() {
        val headers = HttpHeaders.Builder()
            .apply {
                add("Accept", "text/html")
                add("Accept", "application/json")
            }
            .build()
        assertEquals(2, headers.getMany("Accept").size)
    }

    @Test
    fun `builder remove header`() {
        val headers = HttpHeaders.Builder()
            .apply {
                add("X-Keep", "keep")
                add("X-Remove", "remove")
                remove("X-Remove")
            }
            .build()
        assertEquals("keep", headers["X-Keep"]?.root)
        assertNull(headers["X-Remove"])
    }

    @Test
    fun `builder setCookie basic`() {
        val headers = HttpHeaders {
            setCookie("session", "abc123")
        }
        val setCookies = headers.getMany(HttpHeader.SetCookie)
        assertEquals(1, setCookies.size)
    }

    @Test
    fun `builder setCookie with options`() {
        val headers = HttpHeaders {
            setCookie(
                name = "session",
                value = "abc123",
                maxAge = 3600,
                path = "/api",
                secure = true,
                httpOnly = true,
                sameSite = HttpHeaders.SameSite.Strict
            )
        }
        val setCookies = headers.getMany(HttpHeader.SetCookie)
        assertEquals(1, setCookies.size)
        assertEquals(
            "session=abc123; Max-Age=3600; Path=/api; Secure; HttpOnly; SameSite=Strict",
            setCookies.first().toHttpString()
        )
    }

    @Test
    fun `builder setCookie writes Expires as an HTTP date`() {
        val headers = HttpHeaders {
            setCookie(name = "session", value = "abc123", expiresAt = kotlin.time.Instant.parse("2030-01-02T03:04:05Z"))
        }
        assertEquals(
            "session=abc123; Expires=Wed, 2 Jan 2030 03:04:05 GMT; Path=/",
            headers.getMany(HttpHeader.SetCookie).single().toHttpString()
        )
    }

    @Test
    fun `builder setCookie rejects an empty value`() {
        assertFailsWith<IllegalArgumentException> { HttpHeaders { setCookie(name = "session", value = "") } }
    }

    @Test
    fun `builder setCookie with extensions`() {
        val headers = HttpHeaders {
            setCookie(
                name = "test",
                value = "value",
                extensions = mapOf("custom" to "attr", "flag" to null)
            )
        }
        val cookie = headers.getMany(HttpHeader.SetCookie).first()
        assertEquals("attr", cookie.parameters["custom"])
        assertEquals("", cookie.parameters["flag"])
    }

    // ========== Copy Tests ==========

    @Test
    fun `copy modifies headers`() {
        val original = HttpHeaders("X-Original" to "value")
        val copied = original.copy {
            add("X-Added", "new")
        }
        assertEquals("value", copied["X-Original"]?.root)
        assertEquals("new", copied["X-Added"]?.root)
    }

    // ========== Equality and HashCode Tests ==========

    @Test
    fun `equals for same headers`() {
        val headers1 = HttpHeaders("X-Test" to "value")
        val headers2 = HttpHeaders("X-Test" to "value")
        assertEquals(headers1, headers2)
    }

    @Test
    fun `equals for different headers`() {
        val headers1 = HttpHeaders("X-Test" to "value1")
        val headers2 = HttpHeaders("X-Test" to "value2")
        assertFalse(headers1 == headers2)
    }

    @Test
    fun `hashCode same for equal headers`() {
        val headers1 = HttpHeaders("X-Test" to "value")
        val headers2 = HttpHeaders("X-Test" to "value")
        assertEquals(headers1.hashCode(), headers2.hashCode())
    }

    // ========== ToString Tests ==========

    @Test
    fun `toString returns readable format`() {
        val headers = HttpHeaders("X-Test" to "value")
        val str = headers.toString()
        assertTrue(str.contains("x-test") || str.contains("X-Test"))
    }

    // ========== SameSite Enum Tests ==========

    @Test
    fun `SameSite values exist`() {
        assertEquals("Strict", HttpHeaders.SameSite.Strict.name)
        assertEquals("Lax", HttpHeaders.SameSite.Lax.name)
        assertEquals("None", HttpHeaders.SameSite.None.name)
    }
}
