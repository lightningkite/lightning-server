// by Claude
package com.lightningkite.lightningserver.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `PathSegments URL decodes segments`() {
        val segments = PathSegments.parse("/users/john%20doe")
        assertEquals(listOf("users", "john doe"), segments.segments)
    }

    @Test
    fun `PathSegments handles special characters`() {
        val segments = PathSegments.parse("/path/with%2Fslash")
        assertEquals(listOf("path", "with/slash"), segments.segments)
    }

    @Test
    fun `PathSegments toString URL encodes`() {
        val segments = PathSegments(listOf("users", "john doe"))
        assertEquals("users/john+doe", segments.toString())
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
    fun `QueryParameters URL decodes keys and values`() {
        val params = QueryParameters.parse("name=john%20doe&city=New%20York")
        assertEquals("john doe", params["name"])
        assertEquals("New York", params["city"])
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
    fun `QueryParameters toString URL encodes`() {
        val params = QueryParameters(listOf("name" to "john doe", "city" to "New York"))
        val result = params.toString()
        assertTrue(result.contains("name=john+doe"))
        assertTrue(result.contains("city=New+York"))
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

    // ========== PathAndParams Tests ==========

    @Test
    fun `PathAndParams parses path with query string`() {
        val parsed = PathAndParams.parse("/api/users?filter=active")
        assertEquals(listOf("api", "users"), parsed.pathSegments.segments)
        assertEquals("active", parsed.queryParameters["filter"])
    }

    @Test
    fun `PathAndParams parses path without query string`() {
        val parsed = PathAndParams.parse("/api/users")
        assertEquals(listOf("api", "users"), parsed.pathSegments.segments)
        assertTrue(parsed.queryParameters.entries.isEmpty())
    }

    @Test
    fun `PathAndParams handles multiple query params`() {
        val parsed = PathAndParams.parse("/search?q=test&page=1&sort=desc")
        assertEquals("test", parsed.queryParameters["q"])
        assertEquals("1", parsed.queryParameters["page"])
        assertEquals("desc", parsed.queryParameters["sort"])
    }

    @Test
    fun `PathAndParams toString reconstructs URL`() {
        val parsed = PathAndParams.parse("/api/users?filter=active")
        val result = parsed.toString()
        assertTrue(result.contains("api/users"))
        assertTrue(result.contains("filter=active"))
    }

    @Test
    fun `PathAndParams toString without params has no question mark`() {
        val parsed = PathAndParams.parse("/api/users")
        val result = parsed.toString()
        assertTrue(!result.contains("?"))
    }

    // ========== pathHack Tests (existing) ==========

    @Test
    fun pathHack() {
        QueryParameters.parse("path=/my/path?asdf=fdsa")
            .pathHack()
            .let {
                assertEquals("path", it[0].first)
                assertEquals("/my/path", it[0].second)
                assertEquals("asdf", it[1].first)
                assertEquals("fdsa", it[1].second)
            }
    }

    @Test
    fun pathHackWithNoExtraParams() {
        QueryParameters.parse("path=/multiplex")
            .pathHack()
            .let {
                assertEquals(1, it.entries.size)
                assertEquals("path", it[0].first)
                assertEquals("/multiplex", it[0].second)
            }

        QueryParameters.parse("path=/multiplex?param=5")
            .pathHack()
            .let {
                assertEquals(2, it.entries.size)
                assertEquals("path", it[0].first)
                assertEquals("/multiplex", it[0].second)
                assertEquals("param", it[1].first)
                assertEquals("5", it[1].second)
            }
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