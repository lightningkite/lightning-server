// by Claude
package com.lightningkite.lightningserver.http

import kotlinx.serialization.json.Json
import kotlin.test.*

/**
 * Comprehensive tests for parse.kt covering PathSegments, QueryParameters, and PathAndParams.
 */
class ParseComprehensiveTest {

    // ================================
    // PathSegments tests
    // ================================

    @Test
    fun `PathSegments parse simple path`() {
        val segments = PathSegments.parse("/api/users")
        assertEquals(listOf("api", "users"), segments.segments)
    }

    @Test
    fun `PathSegments parse path with trailing slash`() {
        val segments = PathSegments.parse("/api/users/")
        assertEquals(listOf("api", "users", ""), segments.segments)
    }

    @Test
    fun `PathSegments parse root path`() {
        val segments = PathSegments.parse("/")
        assertEquals(listOf(""), segments.segments)
    }

    @Test
    fun `PathSegments parse empty string`() {
        val segments = PathSegments.parse("")
        assertEquals(listOf(""), segments.segments)
    }

    @Test
    fun `PathSegments parse path without leading slash`() {
        val segments = PathSegments.parse("api/users")
        assertEquals(listOf("api", "users"), segments.segments)
    }

    @Test
    fun `PathSegments parse deeply nested path`() {
        val segments = PathSegments.parse("/a/b/c/d/e")
        assertEquals(listOf("a", "b", "c", "d", "e"), segments.segments)
    }


    @Test
    fun `PathSegments toString preserves trailing slash indicator`() {
        val segments = PathSegments(listOf("api", "users", ""))
        val result = segments.toString()
        assertEquals("api/users/", result)
    }

    @Test
    fun `PathSegments roundtrip preserves data`() {
        val original = listOf("api", "users", "john doe", "posts")
        val segments = PathSegments(original)
        val stringForm = segments.toString()
        val reparsed = PathSegments.parse(stringForm)
        assertEquals(original, reparsed.segments)
    }

    @Test
    fun `PathSegments EMPTY is empty list`() {
        assertEquals(emptyList(), PathSegments.EMPTY.segments)
    }

    @Test
    fun `PathSegments implements List interface`() {
        val segments = PathSegments(listOf("a", "b", "c"))
        assertEquals(3, segments.size)
        assertEquals("a", segments[0])
        assertEquals("b", segments[1])
        assertEquals("c", segments[2])
        assertTrue(segments.contains("b"))
        assertEquals(1, segments.indexOf("b"))
    }

    @Test
    fun `PathSegments serialization roundtrip`() {
        val json = Json
        val original = PathSegments(listOf("api", "users"))
        val serialized = json.encodeToString(PathSegments.serializer(), original)
        val deserialized = json.decodeFromString(PathSegments.serializer(), serialized)
        assertEquals(original.segments, deserialized.segments)
    }

    // ================================
    // QueryParameters tests
    // ================================

    @Test
    fun `QueryParameters parse simple params`() {
        val params = QueryParameters.parse("foo=bar&baz=qux")
        assertEquals("bar", params["foo"])
        assertEquals("qux", params["baz"])
    }

    @Test
    fun `QueryParameters parse single param`() {
        val params = QueryParameters.parse("key=value")
        assertEquals("value", params["key"])
        assertEquals(1, params.entries.size)
    }

    @Test
    fun `QueryParameters parse empty string`() {
        val params = QueryParameters.parse("")
        assertEquals(emptyList(), params.entries)
    }

    @Test
    fun `QueryParameters parse param without value`() {
        val params = QueryParameters.parse("flag=")
        assertEquals("", params["flag"])
    }

    @Test
    fun `QueryParameters parse multiple same keys`() {
        val params = QueryParameters.parse("tag=a&tag=b&tag=c")
        // get() returns first value
        assertEquals("a", params["tag"])
        // entries contains all
        val tags = params.entries.filter { it.first == "tag" }.map { it.second }
        assertEquals(listOf("a", "b", "c"), tags)
    }

    @Test
    fun `QueryParameters parse with equals in value`() {
        val params = QueryParameters.parse("expr=a=b")
        assertEquals("a=b", params["expr"])
    }

    @Test
    fun `QueryParameters get returns null for missing key`() {
        val params = QueryParameters.parse("foo=bar")
        assertNull(params["missing"])
    }

    @Test
    fun `QueryParameters roundtrip preserves data`() {
        val original = listOf(
            "foo" to "bar",
            "baz" to "qux",
            "space" to "hello world"
        )
        val params = QueryParameters(original)
        val stringForm = params.toString()
        val reparsed = QueryParameters.parse(stringForm)
        assertEquals(original.size, reparsed.entries.size)
        original.forEach { (key, value) ->
            assertEquals(value, reparsed[key])
        }
    }

    @Test
    fun `QueryParameters EMPTY is empty list`() {
        assertEquals(emptyList(), QueryParameters.EMPTY.entries)
    }

    @Test
    fun `QueryParameters implements List interface`() {
        val params = QueryParameters(listOf("a" to "1", "b" to "2", "c" to "3"))
        assertEquals(3, params.size)
        assertEquals("a" to "1", params[0])
        assertEquals("b" to "2", params[1])
        assertEquals("c" to "3", params[2])
        assertTrue(params.contains("b" to "2"))
    }

    @Test
    fun `QueryParameters serialization roundtrip`() {
        val json = Json
        val original = QueryParameters(listOf("foo" to "bar", "baz" to "qux"))
        val serialized = json.encodeToString(QueryParameters.serializer(), original)
        val deserialized = json.decodeFromString(QueryParameters.serializer(), serialized)
        assertEquals(original.entries, deserialized.entries)
    }

    @Test
    fun `QueryParameters parse handles blank entries`() {
        val params = QueryParameters.parse("foo=bar&&baz=qux")
        assertEquals("bar", params["foo"])
        assertEquals("qux", params["baz"])
        // Blank entries are filtered out
        assertEquals(2, params.entries.size)
    }


    // ================================
    // Edge cases and special characters
    // ================================

    @Test
    fun `PathSegments parse with special characters`() {
        val segments = PathSegments.parse("/api/@user/~settings")
        assertEquals(listOf("api", "@user", "~settings"), segments.segments)
    }

    @Test
    fun `QueryParameters parse preserves order`() {
        val params = QueryParameters.parse("z=3&a=1&m=2")
        assertEquals(listOf("z" to "3", "a" to "1", "m" to "2"), params.entries)
    }

    @Test
    fun `PathSegments with single segment`() {
        val segments = PathSegments.parse("/api")
        assertEquals(listOf("api"), segments.segments)
    }

    @Test
    fun `PathSegments handles consecutive slashes as empty segments`() {
        val segments = PathSegments.parse("/a//b")
        assertEquals(listOf("a", "", "b"), segments.segments)
    }

    @Test
    fun `QueryParameters handles ampersand at end`() {
        val params = QueryParameters.parse("foo=bar&")
        assertEquals(1, params.entries.size)
        assertEquals("bar", params["foo"])
    }

    @Test
    fun `QueryParameters handles ampersand at start`() {
        val params = QueryParameters.parse("&foo=bar")
        assertEquals(1, params.entries.size)
        assertEquals("bar", params["foo"])
    }
}
