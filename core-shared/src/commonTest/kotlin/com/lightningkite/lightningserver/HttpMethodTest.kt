package com.lightningkite.lightningserver

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HttpMethodTest {

    @Test
    fun testStandardMethods() {
        assertEquals("GET", HttpMethod.GET.toString())
        assertEquals("POST", HttpMethod.POST.toString())
        assertEquals("PUT", HttpMethod.PUT.toString())
        assertEquals("PATCH", HttpMethod.PATCH.toString())
        assertEquals("DELETE", HttpMethod.DELETE.toString())
        assertEquals("OPTIONS", HttpMethod.OPTIONS.toString())
        assertEquals("HEAD", HttpMethod.HEAD.toString())
        assertEquals("WEBSOCKET", HttpMethod.WEBSOCKET.toString())
    }

    @Test
    fun testEquality() {
        assertEquals(HttpMethod.GET, HttpMethod.GET)
        assertNotEquals(HttpMethod.GET, HttpMethod.POST)
        assertNotEquals(HttpMethod.PUT, HttpMethod.PATCH)
    }

    @Test
    fun testSerialization() {
        val method = HttpMethod.GET
        val json = Json.encodeToString(method)
        val decoded = Json.decodeFromString<HttpMethod>(json)

        assertEquals(method, decoded)
        assertEquals("GET", decoded.toString())
    }

    @Test
    fun testAllMethodsSerialization() {
        val methods = listOf(
            HttpMethod.GET,
            HttpMethod.POST,
            HttpMethod.PUT,
            HttpMethod.PATCH,
            HttpMethod.DELETE,
            HttpMethod.OPTIONS,
            HttpMethod.HEAD,
            HttpMethod.WEBSOCKET
        )

        for (method in methods) {
            val json = Json.encodeToString(method)
            val decoded = Json.decodeFromString<HttpMethod>(json)
            assertEquals(method, decoded)
        }
    }

    @Test
    fun testToString() {
        assertEquals("GET", HttpMethod.GET.toString())
        assertEquals("POST", HttpMethod.POST.toString())
        assertEquals("WEBSOCKET", HttpMethod.WEBSOCKET.toString())
    }

    @Test
    fun testInWhenExpression() {
        val method = HttpMethod.GET
        val result = when (method) {
            HttpMethod.GET -> "read"
            HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH -> "write"
            HttpMethod.DELETE -> "delete"
            else -> "other"
        }

        assertEquals("read", result)
    }

    @Test
    fun testMethodList() {
        val methods = listOf(
            HttpMethod.GET,
            HttpMethod.POST,
            HttpMethod.PUT
        )

        assertTrue(methods.contains(HttpMethod.GET))
        assertTrue(methods.contains(HttpMethod.POST))
        assertTrue(methods.contains(HttpMethod.PUT))
    }

    @Test
    fun testMethodAsMapKey() {
        val methodMap = mapOf(
            HttpMethod.GET to "read operation",
            HttpMethod.POST to "create operation",
            HttpMethod.DELETE to "delete operation"
        )

        assertEquals("read operation", methodMap[HttpMethod.GET])
        assertEquals("create operation", methodMap[HttpMethod.POST])
        assertEquals("delete operation", methodMap[HttpMethod.DELETE])
    }

    @Test
    fun testJsonSerialization() {
        // Test that serialization uses the raw string value
        val json = Json.encodeToString(HttpMethod.POST)
        assertEquals("\"POST\"", json)
    }

    @Test
    fun testDeserializationFromString() {
        val json = "\"DELETE\""
        val method = Json.decodeFromString<HttpMethod>(json)
        assertEquals(HttpMethod.DELETE, method)
        assertEquals("DELETE", method.toString())
    }
}
