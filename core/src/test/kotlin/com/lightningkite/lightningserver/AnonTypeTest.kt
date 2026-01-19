// by Claude
package com.lightningkite.lightningserver

import com.lightningkite.services.data.KotlinBytesFormat
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for AnonType class.
 */
class AnonTypeTest {

    // Use KotlinBytesFormat for testing
    private val format = KotlinBytesFormat(EmptySerializersModule())

    // ========== Constructor Tests ==========

    @Test
    fun `AnonType from direct value`() {
        val anon = AnonType(format, "Hello", String.serializer())
        val result = anon.value(format, String.serializer())
        assertEquals("Hello", result)
    }

    @Test
    fun `AnonType from serialized bytes`() {
        // First serialize a value
        val bytes = format.encodeToByteArray(String.serializer(), "World")

        // Then create AnonType from bytes
        val anon = AnonType(bytes)
        val result = anon.value(format, String.serializer())
        assertEquals("World", result)
    }

    @Test
    fun `AnonType with null value`() {
        val serializer = String.serializer().nullable
        val anon = AnonType(format, null as String?, serializer)
        val result = anon.value(format, serializer)
        assertEquals(null, result)
    }

    // ========== serializedBytes Tests ==========

    @Test
    fun `serializedBytes returns correct bytes for direct value`() {
        val anon = AnonType(format, 42, Int.serializer())
        val expected = format.encodeToByteArray(Int.serializer(), 42)
        assertTrue(anon.serializedBytes().contentEquals(expected))
    }

    @Test
    fun `serializedBytes returns same bytes when created from bytes`() {
        val original = format.encodeToByteArray(Long.serializer(), 123L)
        val anon = AnonType(original)
        assertTrue(anon.serializedBytes().contentEquals(original))
    }

    @Test
    fun `serializedBytes caches result`() {
        val anon = AnonType(format, "test", String.serializer())
        val first = anon.serializedBytes()
        val second = anon.serializedBytes()
        assertTrue(first === second)  // Same reference
    }

    // ========== value() Tests ==========

    @Test
    fun `value returns direct value without deserialization`() {
        val original = "direct"
        val anon = AnonType(format, original, String.serializer())
        val result = anon.value(format, String.serializer())
        assertEquals(original, result)
    }

    @Test
    fun `value deserializes bytes correctly`() {
        val bytes = format.encodeToByteArray(Double.serializer(), 3.14)
        val anon = AnonType(bytes)
        val result = anon.value(format, Double.serializer())
        assertEquals(3.14, result)
    }

    @Test
    fun `value caches deserialized result`() {
        val bytes = format.encodeToByteArray(Int.serializer(), 100)
        val anon = AnonType(bytes)

        val first = anon.value(format, Int.serializer())
        val second = anon.value(format, Int.serializer())

        assertEquals(first, second)
    }

    // ========== equals Tests ==========

    @Test
    fun `equals for same direct values`() {
        val anon1 = AnonType(format, "same", String.serializer())
        val anon2 = AnonType(format, "same", String.serializer())
        assertEquals(anon1, anon2)
    }

    @Test
    fun `equals for different direct values after serialization`() {
        val anon1 = AnonType(format, "one", String.serializer())
        val anon2 = AnonType(format, "two", String.serializer())
        // Force serialization to populate serializedBytes
        anon1.serializedBytes()
        anon2.serializedBytes()
        assertNotEquals(anon1, anon2)
    }

    @Test
    fun `equals for same bytes`() {
        val bytes = format.encodeToByteArray(Int.serializer(), 42)
        val anon1 = AnonType(bytes.copyOf())
        val anon2 = AnonType(bytes.copyOf())
        assertEquals(anon1, anon2)
    }

    @Test
    fun `equals for different bytes`() {
        val bytes1 = format.encodeToByteArray(Int.serializer(), 1)
        val bytes2 = format.encodeToByteArray(Int.serializer(), 2)
        val anon1 = AnonType(bytes1)
        val anon2 = AnonType(bytes2)
        assertNotEquals(anon1, anon2)
    }

    @Test
    fun `equals with non-AnonType returns false`() {
        val anon = AnonType(format, "test", String.serializer())
        assertFalse(anon.equals("test"))
        assertFalse(anon.equals(null))
    }

    // ========== hashCode Tests ==========

    @Test
    fun `hashCode same for equal direct values`() {
        val anon1 = AnonType(format, "hash", String.serializer())
        val anon2 = AnonType(format, "hash", String.serializer())
        assertEquals(anon1.hashCode(), anon2.hashCode())
    }

    @Test
    fun `hashCode same for equal bytes`() {
        val bytes = format.encodeToByteArray(Int.serializer(), 99)
        val anon1 = AnonType(bytes.copyOf())
        val anon2 = AnonType(bytes.copyOf())
        assertEquals(anon1.hashCode(), anon2.hashCode())
    }

    // ========== toString Tests ==========

    @Test
    fun `toString for direct value`() {
        val anon = AnonType(format, "visible", String.serializer())
        val str = anon.toString()
        assertTrue(str.contains("AnonType"))
        assertTrue(str.contains("visible"))
    }

    @Test
    fun `toString for bytes shows hex`() {
        val bytes = format.encodeToByteArray(Int.serializer(), 42)
        val anon = AnonType(bytes)
        val str = anon.toString()
        assertTrue(str.contains("AnonType"))
    }

    // ========== Complex Type Tests ==========

    @Test
    fun `AnonType with list`() {
        val list = listOf(1, 2, 3, 4, 5)
        val serializer = kotlinx.serialization.builtins.ListSerializer(Int.serializer())
        val anon = AnonType(format, list, serializer)

        val result = anon.value(format, serializer)
        assertEquals(list, result)
    }

    @Test
    fun `AnonType round trip through bytes`() {
        val original = mapOf("key1" to "value1", "key2" to "value2")
        val serializer = kotlinx.serialization.builtins.MapSerializer(String.serializer(), String.serializer())

        // Create from direct value
        val anon1 = AnonType(format, original, serializer)

        // Get bytes and create new instance
        val bytes = anon1.serializedBytes()
        val anon2 = AnonType(bytes)

        // Deserialize and compare
        val result = anon2.value(format, serializer)
        assertEquals(original, result)
    }
}
