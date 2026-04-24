package com.lightningkite.lightningserver

import kotlinx.serialization.json.Json
import kotlin.test.*

class LSErrorTest {

    @Test
    fun testBasicCreation() {
        val error = LSError(
            http = 404,
            detail = "not-found",
            message = "Resource not found"
        )

        assertEquals(404, error.http)
        assertEquals("not-found", error.detail)
        assertEquals("Resource not found", error.message)
        assertEquals("", error.data)
        assertNull(error.stackTrace)
    }

    @Test
    fun testWithAllFields() {
        val error = LSError(
            http = 500,
            detail = "internal-error",
            message = "An internal error occurred",
            data = "{\"field\":\"value\"}",
            stackTrace = "at com.example.Foo.bar(Foo.kt:42)"
        )

        assertEquals(500, error.http)
        assertEquals("internal-error", error.detail)
        assertEquals("An internal error occurred", error.message)
        assertEquals("{\"field\":\"value\"}", error.data)
        assertEquals("at com.example.Foo.bar(Foo.kt:42)", error.stackTrace)
    }

    @Test
    fun testSerialization() {
        val error = LSError(
            http = 400,
            detail = "bad-request",
            message = "Invalid input"
        )

        val json = Json.encodeToString(error)
        val decoded = Json.decodeFromString<LSError>(json)

        assertEquals(error, decoded)
    }

    @Test
    fun testSerializationWithNulls() {
        val error = LSError(
            http = 200,
            detail = "ok"
        )

        val json = Json.encodeToString(error)
        val decoded = Json.decodeFromString<LSError>(json)

        // Verify round-trip serialization works correctly
        assertEquals(error, decoded)
        assertEquals(200, decoded.http)
        assertEquals("ok", decoded.detail)
    }

    @Test
    fun testCommonHttpCodes() {
        val notFound = LSError(http = 404, detail = "not-found")
        val badRequest = LSError(http = 400, detail = "bad-request")
        val unauthorized = LSError(http = 401, detail = "unauthorized")
        val forbidden = LSError(http = 403, detail = "forbidden")
        val internalError = LSError(http = 500, detail = "internal-error")

        assertEquals(404, notFound.http)
        assertEquals(400, badRequest.http)
        assertEquals(401, unauthorized.http)
        assertEquals(403, forbidden.http)
        assertEquals(500, internalError.http)
    }

    @Test
    fun testEquality() {
        val error1 = LSError(http = 404, detail = "not-found", message = "Not found")
        val error2 = LSError(http = 404, detail = "not-found", message = "Not found")
        val error3 = LSError(http = 404, detail = "not-found", message = "Different message")

        assertEquals(error1, error2)
        assertNotEquals(error1, error3)
    }

    @Test
    fun testCopy() {
        val original = LSError(http = 400, detail = "bad-request")
        val withMessage = original.copy(message = "Invalid input")

        assertEquals(400, withMessage.http)
        assertEquals("bad-request", withMessage.detail)
        assertEquals("Invalid input", withMessage.message)
    }

    // Additional edge case tests
    @Test
    fun testEdgeCaseHttpCodes() {
        // Test boundary HTTP codes
        val minCode = LSError(http = 100, detail = "continue")
        val maxCode = LSError(http = 599, detail = "custom-error")

        assertEquals(100, minCode.http)
        assertEquals(599, maxCode.http)
    }

    @Test
    fun testEmptyStrings() {
        val error = LSError(http = 200, detail = "", message = "", data = "")

        assertEquals("", error.detail)
        assertEquals("", error.message)
        assertEquals("", error.data)
    }

    @Test
    fun testJsonDataField() {
        // Verify that data field can hold valid JSON
        val complexJson = """{"nested":{"field":"value"},"array":[1,2,3]}"""
        val error = LSError(http = 400, detail = "validation", data = complexJson)

        assertEquals(complexJson, error.data)
    }
}

class MultiplexMessageTest {

    @Test
    fun testStartMessage() {
        val msg = MultiplexMessage(
            channel = "ch1",
            path = "/api/stream",
            queryParams = mapOf("key" to listOf("value")),
            start = true
        )

        assertEquals("ch1", msg.channel)
        assertEquals("/api/stream", msg.path)
        assertEquals(mapOf("key" to listOf("value")), msg.queryParams)
        assertEquals(true, msg.start)
        assertEquals(false, msg.end)
        assertNull(msg.data)
        assertNull(msg.error)
    }

    @Test
    fun testDataMessage() {
        val msg = MultiplexMessage(
            channel = "ch1",
            data = "{\"value\":42}"
        )

        assertEquals("ch1", msg.channel)
        assertEquals("{\"value\":42}", msg.data)
        assertNull(msg.path)
        assertNull(msg.queryParams)
        assertEquals(false, msg.start)
        assertEquals(false, msg.end)
        assertNull(msg.error)
    }

    @Test
    fun testErrorMessage() {
        val msg = MultiplexMessage(
            channel = "ch1",
            error = "Connection failed"
        )

        assertEquals("ch1", msg.channel)
        assertEquals("Connection failed", msg.error)
        assertNull(msg.data)
        assertNull(msg.path)
        assertNull(msg.queryParams)
        assertEquals(false, msg.start)
        assertEquals(false, msg.end)
    }

    @Test
    fun testEndMessage() {
        val msg = MultiplexMessage(
            channel = "ch1",
            end = true
        )

        assertEquals("ch1", msg.channel)
        assertEquals(true, msg.end)
        assertEquals(false, msg.start)
        assertNull(msg.data)
        assertNull(msg.error)
        assertNull(msg.path)
        assertNull(msg.queryParams)
    }

    @Test
    fun testSerialization() {
        val msg = MultiplexMessage(
            channel = "test-channel",
            data = "test-data"
        )

        val json = Json.encodeToString(msg)
        val decoded = Json.decodeFromString<MultiplexMessage>(json)

        assertEquals(msg, decoded)
    }

    @Test
    fun testSerializationWithAllFields() {
        val msg = MultiplexMessage(
            channel = "ch1",
            path = "/test",
            queryParams = mapOf("a" to listOf("1", "2")),
            start = true,
            end = false,
            data = "data",
            error = "error"
        )

        val json = Json.encodeToString(msg)
        val decoded = Json.decodeFromString<MultiplexMessage>(json)

        assertEquals(msg, decoded)
    }

    @Test
    fun testMinimalMessage() {
        val msg = MultiplexMessage(channel = "minimal")

        val json = Json.encodeToString(msg)
        val decoded = Json.decodeFromString<MultiplexMessage>(json)

        // Verify round-trip serialization works correctly
        assertEquals(msg, decoded)
        assertEquals("minimal", decoded.channel)
        assertEquals(false, decoded.start)
        assertEquals(false, decoded.end)
    }

    @Test
    fun testEquality() {
        val msg1 = MultiplexMessage(channel = "ch1", data = "data")
        val msg2 = MultiplexMessage(channel = "ch1", data = "data")
        val msg3 = MultiplexMessage(channel = "ch2", data = "data")

        assertEquals(msg1, msg2)
        assertNotEquals(msg1, msg3)
    }

    @Test
    fun testCopy() {
        val original = MultiplexMessage(channel = "ch1", start = true)
        val withData = original.copy(data = "new-data", start = false)

        assertEquals("ch1", withData.channel)
        assertEquals("new-data", withData.data)
        assertEquals(false, withData.start)
    }

    // Additional edge case tests
    @Test
    fun testBothDataAndError() {
        // Document behavior when both data and error are set (not recommended but valid)
        val msg = MultiplexMessage(
            channel = "ch1",
            data = "some-data",
            error = "some-error"
        )

        // Both fields are preserved
        assertEquals("some-data", msg.data)
        assertEquals("some-error", msg.error)
    }

    @Test
    fun testStartAndEnd() {
        // Test message that has both start and end set
        val msg = MultiplexMessage(
            channel = "ch1",
            start = true,
            end = true
        )

        assertEquals(true, msg.start)
        assertEquals(true, msg.end)
    }

    @Test
    fun testEmptyQueryParams() {
        val msg = MultiplexMessage(
            channel = "ch1",
            path = "/api",
            queryParams = emptyMap(),
            start = true
        )

        assertEquals(emptyMap(), msg.queryParams)
    }

    @Test
    fun testMultiValueQueryParams() {
        val params = mapOf(
            "key1" to listOf("value1", "value2", "value3"),
            "key2" to emptyList()
        )
        val msg = MultiplexMessage(
            channel = "ch1",
            queryParams = params
        )

        assertEquals(params, msg.queryParams)
    }
}
