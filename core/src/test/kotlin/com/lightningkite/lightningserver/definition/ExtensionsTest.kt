package com.lightningkite.lightningserver.definition

import com.lightningkite.lightningserver.definition.builder.ListRegistry
import com.lightningkite.lightningserver.definition.builder.MapRegistry
import com.lightningkite.lightningserver.pathing.PathSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionsTest {

    object TestKey : MutableExtensions.Key<String>
    object TestDegradingKey : MutableExtensions.DegradingKey<MutableList<String>, List<String>> {
        override fun default(): MutableList<String> = mutableListOf()
        override fun MutableList<String>.include(other: List<String>, pathSpec: com.lightningkite.lightningserver.pathing.PathSpec0) {
            addAll(other)
        }
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
        val list = ext[TestDegradingKey]
        assertEquals(emptyList(), list)

        list.add("item")
        assertEquals(listOf("item"), ext[TestDegradingKey])
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

    var TestExtendable.cached: String by TestKey.cache { "default-${hashCode()}" }

    @Test
    fun `test cache delegate`() {
        val instance = TestExtendable()
        val value1 = instance.cached
        assertTrue(value1.startsWith("default-"))

        val value2 = instance.cached
        assertEquals(value1, value2) // Should be cached

        instance.cached = "new value"
        assertEquals("new value", instance.cached)
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

        val sealed = original.toSealedExtensions()
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
        ext1[TestDegradingKey].add("item1")

        val ext2 = MutableExtensions()
        ext2[TestDegradingKey].add("item2")

        ext2.include(ext1.toSealedExtensions(), PathSpec.root)

        assertEquals("from ext1", ext2[TestKey]) // Regular key should be copied
        assertEquals(listOf("item2", "item1"), ext2[TestDegradingKey]) // Degrading key should be merged
    }
}
