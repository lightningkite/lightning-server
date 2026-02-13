package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.toSealedList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionsTest {
    object TestKey : MutableExtensions.Key<String>
    object TestWritableKey : MutableExtensions.WritableKey<MutableList<String>, List<String>> {
        override fun default(): MutableList<String> = mutableListOf()
        override fun MutableList<String>.include(other: List<String>) {
            addAll(other)
        }
        override fun seal(data: List<String>): List<String> = data.toSealedList()
    }

    @Test
    fun `test basic key get and set`() {
        val ext = MutableExtensions()
        assertNull(ext[TestKey])

        ext[TestKey] = "Hello"
        assertEquals("Hello", ext[TestKey])

        ext[TestKey] = null
        assertNull(ext[TestKey])
    }

    @Test
    fun `test degrading key provides default`() {
        val ext = MutableExtensions()
        val list = ext[TestWritableKey]
        assertEquals(emptyList(), list)

        list.add("item")
        assertEquals(listOf("item"), ext[TestWritableKey])
    }

    class TestExtendable : Extendable {
        override val extensions = MutableExtensions()
    }

    var TestExtendable.testProp: String? by TestKey

    @Test
    fun `test extensions delegation`() {
        val instance = TestExtendable()
        assertNull(instance.testProp)

        instance.testProp = "value"
        assertEquals("value", instance.testProp)
    }

    @Test
    fun `test getOrPut`() {
        val ext = MutableExtensions()
        var computeCount = 0

        val value1 = ext.getOrPut(TestKey) {
            computeCount++
            "computed"
        }
        assertEquals("computed", value1)
        assertEquals(1, computeCount)

        val value2 = ext.getOrPut(TestKey) {
            computeCount++
            "computed again"
        }
        assertEquals("computed", value2) // Should return existing value
        assertEquals(1, computeCount) // Should not compute again
    }

    object TestMapExt : MapRegistryExtension<String, Int>

    val TestExtendable.map: MapRegistry<String, Int> by TestMapExt

    @Test
    fun `test MapRegistryExtension`() {
        val instance = TestExtendable()
        assertEquals(emptyMap(), instance.map)

        instance.map.register("key", 42)
        assertEquals(mapOf("key" to 42), instance.map)
    }

    object TestListExt : ListRegistryExtension<String>

    val TestExtendable.list: ListRegistry<String> by TestListExt

    @Test
    fun `test ListRegistryExtension`() {
        val instance = TestExtendable()
        assertEquals(emptyList(), instance.list)

        instance.list.register("item")
        assertEquals(listOf("item"), instance.list)
    }

    @Test
    fun `test toMutableExtensions creates copy`() {
        val original = MutableExtensions()
        original[TestKey] = "original"

        val sealed = original.sealed()
        val copy = sealed.toMutableExtensions()

        assertEquals("original", copy[TestKey])

        copy[TestKey] = "modified"
        assertEquals("original", sealed[TestKey]) // Original should be unchanged
        assertEquals("modified", copy[TestKey])
    }

    @Test
    fun `test include merges extensions`() {
        val ext1 = MutableExtensions()
        ext1[TestKey] = "from ext1"
        ext1[TestWritableKey].add("item1")

        val ext2 = MutableExtensions()
        ext2[TestWritableKey].add("item2")

        ext2.include(ext1.sealed())

        assertEquals("from ext1", ext2[TestKey]) // Regular key should be copied
        assertEquals(listOf("item2", "item1"), ext2[TestWritableKey]) // Degrading key should be merged
    }

    // ========== Additional tests for Extensions.ext.kt coverage - by Claude ==========

    // Test class for Extended (read-only) interface
    class TestExtended(override val extensions: Extensions) : Extended

    val TestExtended.testProp: String? by TestKey

    @Test
    fun `test Extensions Key getValue for Extended returns value when present`() {
        val ext = MutableExtensions()
        ext[TestKey] = "hello"
        val instance = TestExtended(ext.sealed())

        assertEquals("hello", instance.testProp)
    }

    @Test
    fun `test Extensions Key getValue for Extended returns null when absent`() {
        val ext = MutableExtensions()
        val instance = TestExtended(ext.sealed())

        assertNull(instance.testProp)
    }

    @Test
    fun `test MutableExtensions Key setValue removes value when set to null`() {
        val instance = TestExtendable()
        instance.testProp = "value"
        assertEquals("value", instance.testProp)

        instance.testProp = null
        assertNull(instance.testProp)
    }

    // DegradingKey delegation tests
    val TestExtendable.degradingList: MutableList<String> by TestWritableKey
    val TestExtended.degradingList: List<String> by TestWritableKey

    @Test
    fun `test DegradingKey getValue for Extendable returns mutable type`() {
        val instance = TestExtendable()
        val list = instance.degradingList

        // Should be able to modify the list
        list.add("item1")
        assertEquals(listOf("item1"), instance.degradingList)
    }

    @Test
    fun `test DegradingKey getValue for Extended returns default when absent`() {
        val ext = MutableExtensions()
        val instance = TestExtended(ext.sealed())

        // Should return default (empty list)
        assertEquals(emptyList(), instance.degradingList)
    }

    @Test
    fun `test DegradingKey getValue for Extended returns existing value when present`() {
        val ext = MutableExtensions()
        ext[TestWritableKey].add("existing")
        val instance = TestExtended(ext.sealed())

        assertEquals(listOf("existing"), instance.degradingList)
    }

    @Test
    fun `test DegradingKey getValue for Extended each call returns new default when absent`() {
        // Note: This test documents the behavior that each access on Extended creates a new default
        // when the value is not present in extensions
        val ext = MutableExtensions()
        val instance = TestExtended(ext.sealed())

        val list1 = instance.degradingList
        val list2 = instance.degradingList

        // Each call creates a new default instance because it's not stored
        assertTrue(list1 !== list2)
    }

    // MapRegistryExtension tests for Extended
    val TestExtended.map: Map<String, Int> by TestMapExt

    @Test
    fun `test MapRegistryExtension for Extended returns empty map when absent`() {
        val ext = MutableExtensions()
        val instance = TestExtended(ext.sealed())

        assertEquals(emptyMap(), instance.map)
    }

    @Test
    fun `test MapRegistryExtension for Extended returns existing values`() {
        val mutableInstance = TestExtendable()
        mutableInstance.map.register("key1", 1)
        mutableInstance.map.register("key2", 2)

        val readOnlyInstance = TestExtended(mutableInstance.extensions.sealed())

        assertEquals(mapOf("key1" to 1, "key2" to 2), readOnlyInstance.map)
    }

    @Test
    fun `test MapRegistryExtension include merges entries`() {
        val ext1 = MutableExtensions()
        ext1[TestMapExt].register("key1", 1)

        val ext2 = MutableExtensions()
        ext2[TestMapExt].register("key2", 2)

        ext2.include(ext1.sealed())

        assertEquals(mapOf("key2" to 2, "key1" to 1), ext2[TestMapExt])
    }

    // ListRegistryExtension tests for Extended
    val TestExtended.list: List<String> by TestListExt

    @Test
    fun `test ListRegistryExtension for Extended returns empty list when absent`() {
        val ext = MutableExtensions()
        val instance = TestExtended(ext.sealed())

        assertEquals(emptyList(), instance.list)
    }

    @Test
    fun `test ListRegistryExtension for Extended returns existing values`() {
        val mutableInstance = TestExtendable()
        mutableInstance.list.register("item1")
        mutableInstance.list.register("item2")

        val readOnlyInstance = TestExtended(mutableInstance.extensions.sealed())

        assertEquals(listOf("item1", "item2"), readOnlyInstance.list)
    }

    @Test
    fun `test ListRegistryExtension include merges entries`() {
        val ext1 = MutableExtensions()
        ext1[TestListExt].register("item1")

        val ext2 = MutableExtensions()
        ext2[TestListExt].register("item2")

        ext2.include(ext1.sealed())

        assertEquals(listOf("item2", "item1"), ext2[TestListExt])
    }

    // getOrPut edge cases
    @Test
    fun `test getOrPut returns existing value without computing default`() {
        val ext = MutableExtensions()
        ext[TestKey] = "existing"
        var computed = false

        val result = ext.getOrPut(TestKey) {
            computed = true
            "computed"
        }

        assertEquals("existing", result)
        assertEquals(false, computed)
    }

    // ========== Additional tests for Extensions.kt core functionality - by Claude ==========

    @Test
    fun `test MutableExtensions constructor copies initial extensions`() {
        val original = MutableExtensions()
        original[TestKey] = "value"
        original[TestWritableKey].add("item")

        val copy = MutableExtensions(original.sealed())

        assertEquals("value", copy[TestKey])
        assertEquals(listOf("item"), copy[TestWritableKey])

        // Modifying copy should not affect original (sealed)
        copy[TestKey] = "modified"
        assertEquals("value", original[TestKey])
    }

    @Test
    fun `test toSealedExtensions prevents modification`() {
        val mutable = MutableExtensions()
        mutable[TestKey] = "value"
        val sealed = mutable.sealed()

        // Sealed extensions should throw on modification attempt
        // Note: This test verifies the sealing behavior - the internal SealableMap
        // throws IllegalStateException when modified after sealing
        try {
            (sealed as MutableExtensions)[TestKey] = "new"
            assertTrue(false, "Should have thrown IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("sealed") == true)
        }
    }

    @Test
    fun `test include preserves existing regular key value`() {
        val ext1 = MutableExtensions()
        ext1[TestKey] = "from ext1"

        val ext2 = MutableExtensions()
        ext2[TestKey] = "from ext2" // Existing value

        ext2.include(ext1.sealed())

        // putIfAbsent should preserve the existing value in ext2
        assertEquals("from ext2", ext2[TestKey])
    }

    @Test
    fun `test entries returns all key-value pairs`() {
        val ext = MutableExtensions()
        ext[TestKey] = "value"
        ext[TestWritableKey].add("item")

        val entries = ext.entries
        assertEquals(2, entries.size)

        val keySet = entries.map { it.key }.toSet()
        assertTrue(keySet.contains(TestKey))
        assertTrue(keySet.contains(TestWritableKey))
    }

    @Test
    fun `test include skips null DegradingKey values`() {
        // This tests the continue statement at line 147 in Extensions.kt
        // When extensions[key] returns null for a DegradingKey, it should skip
        val ext1 = MutableExtensions()
        // ext1 has TestDegradingKey but we don't add anything to it
        // so when we access via Extensions interface (not MutableExtensions), it returns null

        val ext2 = MutableExtensions()
        ext2[TestWritableKey].add("original")

        // Create a mock Extensions that returns null for the DegradingKey
        val mockExtensions = object : Extensions {
            override fun <T : Any> get(key: Extensions.Key<T>): T? = null
            override val entries: Set<Extensions.Entry<*>>
                get() = setOf(Extensions.Entry(TestWritableKey, emptyList()))
        }

        ext2.include(mockExtensions)

        // Original value should be preserved since include skipped when get returned null
        assertEquals(listOf("original"), ext2[TestWritableKey])
    }
}
