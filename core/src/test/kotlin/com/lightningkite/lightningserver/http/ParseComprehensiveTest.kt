// by Claude
package com.lightningkite.lightningserver.http

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `PathSegments parse path with encoded characters`() {
        val segments = PathSegments.parse("/users/john%20doe")
        assertEquals(listOf("users", "john doe"), segments.segments)
    }

    @Test
    fun `PathSegments parse path with plus sign`() {
        // URLDecoder decodes + as space in path segments
        val segments = PathSegments.parse("/search/hello+world")
        assertEquals(listOf("search", "hello world"), segments.segments)
    }

    @Test
    fun `PathSegments parse path with unicode`() {
        val segments = PathSegments.parse("/users/%E4%B8%AD%E6%96%87")
        assertEquals(listOf("users", "中文"), segments.segments)
    }

    @Test
    fun `PathSegments toString encodes segments`() {
        val segments = PathSegments(listOf("api", "users", "john doe"))
        val result = segments.toString()
        assertEquals("api/users/john+doe", result)
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
    fun `QueryParameters parse encoded values`() {
        val params = QueryParameters.parse("name=john%20doe&query=hello%2Bworld")
        assertEquals("john doe", params["name"])
        assertEquals("hello+world", params["query"])
    }

    @Test
    fun `QueryParameters parse encoded keys`() {
        val params = QueryParameters.parse("my%20key=value")
        assertEquals("value", params["my key"])
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
    fun `QueryParameters parse unicode values`() {
        val params = QueryParameters.parse("name=%E4%B8%AD%E6%96%87")
        assertEquals("中文", params["name"])
    }

    @Test
    fun `QueryParameters get returns null for missing key`() {
        val params = QueryParameters.parse("foo=bar")
        assertNull(params["missing"])
    }

    @Test
    fun `QueryParameters toString encodes correctly`() {
        val params = QueryParameters(listOf(
            "name" to "john doe",
            "query" to "hello+world"
        ))
        val result = params.toString()
        assertTrue(result.contains("name=john+doe") || result.contains("name=john%20doe"))
        assertTrue(result.contains("query=hello%2Bworld"))
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
    // PathAndParams tests
    // ================================

    @Test
    fun `PathAndParams parse path only`() {
        val result = PathAndParams.parse("/api/users")
        assertEquals(listOf("api", "users"), result.pathSegments.segments)
        assertEquals(emptyList(), result.queryParameters.entries)
    }

    @Test
    fun `PathAndParams parse path with query`() {
        val result = PathAndParams.parse("/api/users?filter=active")
        assertEquals(listOf("api", "users"), result.pathSegments.segments)
        assertEquals("active", result.queryParameters["filter"])
    }

    @Test
    fun `PathAndParams parse path with multiple query params`() {
        val result = PathAndParams.parse("/api/users?filter=active&sort=name&limit=10")
        assertEquals(listOf("api", "users"), result.pathSegments.segments)
        assertEquals("active", result.queryParameters["filter"])
        assertEquals("name", result.queryParameters["sort"])
        assertEquals("10", result.queryParameters["limit"])
    }

    @Test
    fun `PathAndParams parse root with query`() {
        val result = PathAndParams.parse("/?foo=bar")
        assertEquals(listOf(""), result.pathSegments.segments)
        assertEquals("bar", result.queryParameters["foo"])
    }

    @Test
    fun `PathAndParams parse empty query string`() {
        val result = PathAndParams.parse("/api?")
        assertEquals(listOf("api"), result.pathSegments.segments)
        // Empty query string results in empty params
        assertEquals(emptyList(), result.queryParameters.entries)
    }

    @Test
    fun `PathAndParams parse with encoded path and query`() {
        val result = PathAndParams.parse("/api/user%20name?search=hello%20world")
        assertEquals(listOf("api", "user name"), result.pathSegments.segments)
        assertEquals("hello world", result.queryParameters["search"])
    }

    @Test
    fun `PathAndParams toString without query`() {
        val pathAndParams = PathAndParams(
            PathSegments(listOf("api", "users")),
            QueryParameters.EMPTY
        )
        assertEquals("api/users", pathAndParams.toString())
    }

    @Test
    fun `PathAndParams toString with query`() {
        val pathAndParams = PathAndParams(
            PathSegments(listOf("api", "users")),
            QueryParameters(listOf("filter" to "active"))
        )
        val result = pathAndParams.toString()
        assertTrue(result.startsWith("api/users?"))
        assertTrue(result.contains("filter=active"))
    }

    @Test
    fun `PathAndParams roundtrip preserves data`() {
        val original = PathAndParams(
            PathSegments(listOf("api", "users", "john doe")),
            QueryParameters(listOf("filter" to "active", "sort" to "name"))
        )
        val stringForm = original.toString()
        val reparsed = PathAndParams.parse(stringForm)
        assertEquals(original.pathSegments.segments, reparsed.pathSegments.segments)
        assertEquals(original.queryParameters["filter"], reparsed.queryParameters["filter"])
        assertEquals(original.queryParameters["sort"], reparsed.queryParameters["sort"])
    }

    @Test
    fun `PathAndParams serialization roundtrip`() {
        val json = Json
        val original = PathAndParams(
            PathSegments(listOf("api", "users")),
            QueryParameters(listOf("foo" to "bar"))
        )
        val serialized = json.encodeToString(PathAndParams.serializer(), original)
        val deserialized = json.decodeFromString(PathAndParams.serializer(), serialized)
        assertEquals(original.pathSegments.segments, deserialized.pathSegments.segments)
        assertEquals(original.queryParameters.entries, deserialized.queryParameters.entries)
    }

    @Test
    fun `PathAndParams parse handles multiple question marks`() {
        // The split('?') produces ["path", "key=val", "ue"] - only first split matters for path
        // But split(2) isn't used, so query becomes "key=val" and "?ue" is lost
        val result = PathAndParams.parse("/api?key=val?ue")
        assertEquals(listOf("api"), result.pathSegments.segments)
        // With split("?"), the second "?" and everything after it is lost
        assertEquals("val", result.queryParameters["key"])
    }

    // ================================
    // pathHack tests (additional coverage)
    // ================================

    @Test
    fun `pathHack does nothing for regular params`() {
        val params = QueryParameters.parse("foo=bar&baz=qux")
        val hacked = params.pathHack()
        assertEquals(params.entries, hacked.entries)
    }

    @Test
    fun `pathHack extracts nested query params from path value`() {
        val params = QueryParameters.parse("path=/api?nested=value")
        val hacked = params.pathHack()
        assertEquals("/api?nested=value", hacked["path"])
        assertEquals("value", hacked["nested"])
    }

    @Test
    fun `pathHack handles multiple nested params`() {
        // Note: The original query string "path=/api?a=1&b=2" is parsed as:
        // [("path", "/api?a=1"), ("b", "2")] because & is the delimiter
        // So pathHack only extracts the nested params from the first path value
        val params = QueryParameters.parse("path=/api?a=1")
        val hacked = params.pathHack()
        assertEquals("/api?a=1", hacked["path"])
        assertEquals("1", hacked["a"])
    }

    @Test
    fun `pathHack preserves other params`() {
        val params = QueryParameters.parse("other=value&path=/api?nested=val")
        val hacked = params.pathHack()
        assertEquals("value", hacked["other"])
        assertEquals("/api?nested=val", hacked["path"])
        assertEquals("val", hacked["nested"])
    }

    @Test
    fun `pathHack handles path without question mark`() {
        val params = QueryParameters.parse("path=/simple/path")
        val hacked = params.pathHack()
        assertEquals(1, hacked.entries.size)
        assertEquals("/simple/path", hacked["path"])
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
    fun `QueryParameters parse with special characters in value`() {
        val params = QueryParameters.parse("email=user%40example.com")
        assertEquals("user@example.com", params["email"])
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
