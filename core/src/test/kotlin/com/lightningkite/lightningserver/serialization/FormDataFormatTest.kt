package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.modules.EmptySerializersModule
import kotlin.test.*

/**
 * Tests for FormDataFormat encoding/decoding.
 */
class FormDataFormatTest {

    private val format = FormDataFormat(EmptySerializersModule())

    @Serializable
    data class SimpleData(val name: String, val age: Int)

    @Serializable
    data class ComplexData(
        val text: String,
        val number: Int,
        val flag: Boolean,
        val optional: String? = null,
    )

    @Serializable
    enum class Color { RED, GREEN, BLUE }

    @Test
    fun `test encode simple data to string`() {
        val data = SimpleData("John Doe", 30)
        val encoded = format.encodeToString(SimpleData.serializer(), data)
        assertTrue(encoded.contains("name=John+Doe") || encoded.contains("name=John%20Doe"))
        assertTrue(encoded.contains("age=30"))
    }

    @Test
    fun `test decode simple data from string`() {
        val encoded = "name=John+Doe&age=30"
        val data = format.decodeFromString(SimpleData.serializer(), encoded)
        assertEquals("John Doe", data.name)
        assertEquals(30, data.age)
    }

    @Test
    fun `test encode with special characters`() {
        val data = SimpleData("John & Jane", 25)
        val encoded = format.encodeToString(SimpleData.serializer(), data)
        val decoded = format.decodeFromString(SimpleData.serializer(), encoded)
        assertEquals(data.name, decoded.name)
        assertEquals(data.age, decoded.age)
    }

    @Test
    fun `test encode to map`() {
        val data = SimpleData("Alice", 28)
        val map = format.encodeToMap(SimpleData.serializer(), data)
        assertEquals("Alice", map["name"])
        assertEquals("28", map["age"])
    }

    @Test
    fun `test decode from map`() {
        val map = mapOf("name" to "Bob", "age" to "35")
        val data = format.decodeFromMap(SimpleData.serializer(), map)
        assertEquals("Bob", data.name)
        assertEquals(35, data.age)
    }

    @Test
    fun `test encode to list`() {
        val data = SimpleData("Charlie", 40)
        val list = format.encodeToList(SimpleData.serializer(), data)
        assertTrue(list.contains("name" to "Charlie"))
        assertTrue(list.contains("age" to "40"))
    }

    @Test
    fun `test decode from list`() {
        val list = listOf("name" to "Dave", "age" to "45")
        val data = format.decodeFromList(SimpleData.serializer(), list)
        assertEquals("Dave", data.name)
        assertEquals(45, data.age)
    }

    @Test
    fun `test primitive wrapping for String`() {
        val value = "test"
        val encoded = format.encodeToMap(String.serializer(), value)
        assertTrue(encoded.containsKey("value"))
        val decoded = format.decodeFromMap(String.serializer(), encoded)
        assertEquals(value, decoded)
    }

    @Test
    fun `test primitive wrapping for Int`() {
        val value = 42
        val encoded = format.encodeToMap(Int.serializer(), value)
        assertTrue(encoded.containsKey("value"))
        val decoded = format.decodeFromMap(Int.serializer(), encoded)
        assertEquals(value, decoded)
    }

    @Test
    fun `test enum wrapping`() {
        val value = Color.RED
        val encoded = format.encodeToMap(Color.serializer(), value)
        assertTrue(encoded.containsKey("value"))
        val decoded = format.decodeFromMap(Color.serializer(), encoded)
        assertEquals(value, decoded)
    }

    @Test
    fun `test complex data with optional field`() {
        val data = ComplexData(
            text = "Hello",
            number = 123,
            flag = true,
            optional = "present"
        )
        val encoded = format.encodeToString(ComplexData.serializer(), data)
        val decoded = format.decodeFromString(ComplexData.serializer(), encoded)
        assertEquals(data, decoded)
    }

    @Test
    fun `test complex data without optional field`() {
        val data = ComplexData(
            text = "Hello",
            number = 123,
            flag = false
        )
        val encoded = format.encodeToString(ComplexData.serializer(), data)
        val decoded = format.decodeFromString(ComplexData.serializer(), encoded)
        assertEquals(data.text, decoded.text)
        assertEquals(data.number, decoded.number)
        assertEquals(data.flag, decoded.flag)
    }
}
