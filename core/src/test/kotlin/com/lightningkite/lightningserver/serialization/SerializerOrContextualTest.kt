package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for serializerOrContextual utility functions.
 */
class SerializerOrContextualTest {

    @Serializable
    data class TestData(val value: String)

    @Test
    fun `test serializerOrContextual for Serializable type`() {
        val serializer = serializerOrContextual<TestData>()
        assertNotNull(serializer)
        assertNotNull(serializer.descriptor)
    }

    @Test
    fun `test serializerOrContextual for primitive type`() {
        val serializer = serializerOrContextual<String>()
        assertNotNull(serializer)
    }

    @Test
    fun `test serializerOrContextual for nullable type`() {
        val serializer = serializerOrContextual<String?>()
        assertNotNull(serializer)
        assertTrue(serializer.descriptor.isNullable)
    }

    @Test
    fun `test serializerOrContextual for List`() {
        val serializer = serializerOrContextual<List<String>>()
        assertNotNull(serializer)
    }

    @Test
    fun `test serializerOrContextual for Map`() {
        val serializer = serializerOrContextual<Map<String, Int>>()
        assertNotNull(serializer)
    }

    @Test
    fun `test serializerOrContextual for generic data class`() {
        val serializer = serializerOrContextual<TestData>()
        assertNotNull(serializer)
        val data = TestData("test")
        val json = kotlinx.serialization.json.Json.encodeToString(serializer, data)
        assertNotNull(json)
        assertTrue(json.contains("\"value\":\"test\""))
    }

    @Test
    fun `test serializerOrContextual for Unit`() {
        val serializer = serializerOrContextual<Unit>()
        assertNotNull(serializer)
    }

    @Test
    fun `test serializerOrContextual for Boolean`() {
        val serializer = serializerOrContextual<Boolean>()
        assertNotNull(serializer)
    }

    @Test
    fun `test serializerOrContextual for Int`() {
        val serializer = serializerOrContextual<Int>()
        assertNotNull(serializer)
    }

    @Test
    fun `test serializerOrContextual for Double`() {
        val serializer = serializerOrContextual<Double>()
        assertNotNull(serializer)
    }
}
