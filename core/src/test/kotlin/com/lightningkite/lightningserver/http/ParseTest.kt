// by Claude
package com.lightningkite.lightningserver.http

import kotlin.test.*

class ParseTest {

    // ========== PathSegments Tests ==========

    @Test
    fun `PathSegments parses simple path`() {
        val segments = PathSegments.parse("/api/users")
        assertEquals(listOf("api", "users"), segments.segments)
    }

    @Test
    fun `PathSegments parses path without leading slash`() {
        val segments = PathSegments.parse("api/users")
        assertEquals(listOf("api", "users"), segments.segments)
    }


    @Test
    fun `PathSegments EMPTY has no segments`() {
        assertTrue(PathSegments.EMPTY.segments.isEmpty())
    }

    @Test
    fun `PathSegments preserves trailing slash as empty segment`() {
        val segments = PathSegments.parse("/api/users/")
        assertEquals(listOf("api", "users", ""), segments.segments)
    }

    @Test
    fun `PathSegments delegates List methods`() {
        val segments = PathSegments.parse("/a/b/c")
        assertEquals(3, segments.size)
        assertEquals("a", segments[0])
        assertEquals("c", segments.last())
        assertTrue(segments.contains("b"))
    }

    // ========== QueryParameters Tests ==========

    @Test
    fun `QueryParameters parses simple params`() {
        val params = QueryParameters.parse("key=value")
        assertEquals("value", params["key"])
    }

    @Test
    fun `QueryParameters parses multiple params`() {
        val params = QueryParameters.parse("a=1&b=2&c=3")
        assertEquals("1", params["a"])
        assertEquals("2", params["b"])
        assertEquals("3", params["c"])
    }

    @Test
    fun `QueryParameters handles empty value`() {
        val params = QueryParameters.parse("flag=")
        assertEquals("", params["flag"])
    }

    @Test
    fun `QueryParameters handles missing equals`() {
        val params = QueryParameters.parse("flag")
        assertEquals("", params["flag"])
    }

    @Test
    fun `QueryParameters handles value with equals sign`() {
        val params = QueryParameters.parse("data=abc=def")
        assertEquals("abc=def", params["data"])
    }

    @Test
    fun `QueryParameters get returns null for missing key`() {
        val params = QueryParameters.parse("a=1")
        assertNull(params["missing"])
    }

    @Test
    fun `QueryParameters supports duplicate keys`() {
        val params = QueryParameters.parse("tag=a&tag=b&tag=c")
        // get() returns first value
        assertEquals("a", params["tag"])
        // entries contains all
        assertEquals(3, params.entries.count { it.first == "tag" })
    }

    @Test
    fun `QueryParameters EMPTY has no entries`() {
        assertTrue(QueryParameters.EMPTY.entries.isEmpty())
    }

    @Test
    fun `QueryParameters delegates List methods`() {
        val params = QueryParameters.parse("a=1&b=2")
        assertEquals(2, params.size)
        assertEquals("a" to "1", params[0])
    }

    // ========== Edge Cases ==========

    @Test
    fun `QueryParameters handles blank entries`() {
        val params = QueryParameters.parse("a=1&&b=2")
        assertEquals("1", params["a"])
        assertEquals("2", params["b"])
    }

    @Test
    fun `PathSegments handles deeply nested path`() {
        val segments = PathSegments.parse("/a/b/c/d/e/f/g")
        assertEquals(7, segments.size)
    }
}