// by Claude
package com.lightningkite.lightningserver.http

import kotlin.test.*

/**
 * Tests for HttpHeaderValue parsing and formatting.
 */
class HttpHeaderValueTest {

    // ========== Standard Header Parsing Tests ==========

    @Test
    fun `parse simple header value`() {
        val value = HttpHeaderValue.parse("Content-Type", "application/json")
        assertEquals("application/json", value.root)
        assertTrue(value.parameters.isEmpty())
    }

    @Test
    fun `parse header with one parameter`() {
        val value = HttpHeaderValue.parse("Content-Type", "text/html; charset=utf-8")
        assertEquals("text/html", value.root)
        assertEquals("utf-8", value.parameters["charset"])
    }

    @Test
    fun `parse header with multiple parameters`() {
        val value = HttpHeaderValue.parse("Content-Disposition", "attachment; filename=file.txt; size=1234")
        assertEquals("attachment", value.root)
        assertEquals("file.txt", value.parameters["filename"])
        assertEquals("1234", value.parameters["size"])
    }

    @Test
    fun `parse header with spaces around parameters`() {
        val value = HttpHeaderValue.parse("Content-Type", "text/plain ;  encoding = utf-8 ")
        assertEquals("text/plain ", value.root)
        assertEquals("utf-8", value.parameters["encoding"])
    }

    @Test
    fun `parse header with empty parameter value`() {
        // Parameters without values (just the key name)
        // Note: Due to implementation, valueless parameters get their name as both key and value
        // (substringAfter returns original if delimiter not found)
        val value = HttpHeaderValue.parse("Custom-Header", "value; flag")
        assertEquals("value", value.root)
        // The implementation uses substringAfter('=') which returns original string if no '='
        assertEquals("flag", value.parameters["flag"])
    }

    // ========== Cookie Parsing Tests ==========

    @Test
    fun `parse Cookie header`() {
        val value = HttpHeaderValue.parse("Cookie", "session=abc123; user=john")
        assertEquals("", value.root)
        assertEquals("abc123", value.parameters["session"])
        assertEquals("john", value.parameters["user"])
    }

    @Test
    fun `parse Cookie header case insensitive`() {
        val value = HttpHeaderValue.parse("cookie", "token=xyz")
        assertEquals("", value.root)
        assertEquals("xyz", value.parameters["token"])
    }

    @Test
    fun `parse single cookie`() {
        val value = HttpHeaderValue.parse("Cookie", "single=value")
        assertEquals("value", value.parameters["single"])
    }

    @Test
    fun `parse Set-Cookie header`() {
        val value = HttpHeaderValue.parse("Set-Cookie", "session=abc; path=/; HttpOnly")
        assertEquals("", value.root)
        assertEquals("abc", value.parameters["session"])
        assertEquals("/", value.parameters["path"])
        assertTrue(value.parameters.containsKey("HttpOnly"))
    }

    // ========== toHttpString Tests ==========

    @Test
    fun `toHttpString simple value`() {
        val value = HttpHeaderValue("application/json", emptyMap())
        assertEquals("application/json", value.toHttpString())
    }

    @Test
    fun `toHttpString with parameters`() {
        val value = HttpHeaderValue("text/html", mapOf("charset" to "utf-8"))
        val result = value.toHttpString()
        assertTrue(result.contains("text/html"))
        assertTrue(result.contains("charset=utf-8"))
    }

    @Test
    fun `toHttpString cookie style no root`() {
        val value = HttpHeaderValue("", mapOf("session" to "abc", "path" to "/"))
        val result = value.toHttpString()
        assertTrue(result.contains("session=abc"))
        assertTrue(result.contains("path=/"))
    }

    @Test
    fun `toHttpString with empty parameter value`() {
        val value = HttpHeaderValue("value", mapOf("flag" to ""))
        val result = value.toHttpString()
        assertTrue(result.contains("flag"))
        // Empty value should render as just the key
    }

    @Test
    fun `toHttpString empty value with no parameters`() {
        val value = HttpHeaderValue("", emptyMap())
        assertEquals("", value.toHttpString())
    }

    // ========== toString Tests ==========

    @Test
    fun `toString delegates to toHttpString`() {
        val value = HttpHeaderValue("application/json", mapOf("charset" to "utf-8"))
        assertEquals(value.toHttpString(), value.toString())
    }

    // ========== Data Class Tests ==========

    @Test
    fun `data class equality`() {
        val value1 = HttpHeaderValue("text/plain", mapOf("a" to "b"))
        val value2 = HttpHeaderValue("text/plain", mapOf("a" to "b"))
        assertEquals(value1, value2)
        assertEquals(value1.hashCode(), value2.hashCode())
    }

    @Test
    fun `data class copy`() {
        val original = HttpHeaderValue("original", mapOf("key" to "value"))
        val copied = original.copy(root = "modified")
        assertEquals("modified", copied.root)
        assertEquals(original.parameters, copied.parameters)
    }

    // ========== Edge Cases ==========

    @Test
    fun `parse empty string`() {
        val value = HttpHeaderValue.parse("X-Test", "")
        assertEquals("", value.root)
        assertTrue(value.parameters.isEmpty())
    }

    @Test
    fun `parse value with multiple equals signs`() {
        // Base64 values often have = at the end
        val value = HttpHeaderValue.parse("X-Token", "token; data=abc==")
        assertEquals("token", value.root)
        assertEquals("abc==", value.parameters["data"])
    }
}
