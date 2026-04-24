package com.lightningkite.lightningserver.definition.builder

import kotlin.test.*

class ListRegistryTest {

    @Test
    fun `test register adds items`() {
        val registry = ListRegistry<String>()
        assertTrue(registry.isEmpty())

        registry.register("a")
        assertEquals(listOf("a"), registry)

        registry.register("b")
        assertEquals(listOf("a", "b"), registry)
    }

    @Test
    fun `test include adds multiple items`() {
        val registry = ListRegistry<String>()
        registry.include(listOf("a", "b", "c"))

        assertEquals(listOf("a", "b", "c"), registry)
    }

    @Test
    fun `test registry preserves order`() {
        val registry = ListRegistry<Int>()
        registry.register(3)
        registry.register(1)
        registry.register(2)

        assertEquals(listOf(3, 1, 2), registry)
    }

    @Test
    fun `test duplicate items are allowed`() {
        val registry = ListRegistry<String>()
        registry.register("a")
        registry.register("a")
        registry.register("a")

        assertEquals(listOf("a", "a", "a"), registry)
    }

    @Test
    fun `test buildListRegistry creates immutable list`() {
        val list = buildListRegistry<String> {
            register("a")
            register("b")
            register("c")
        }

        assertEquals(listOf("a", "b", "c"), list)
    }

    @Test
    fun `test ListRegistry constructor with initial items`() {
        val registry = ListRegistry(listOf("a", "b"))
        assertEquals(listOf("a", "b"), registry)

        registry.register("c")
        assertEquals(listOf("a", "b", "c"), registry)
    }

    // by Claude - Tests for varargs constructor
    @Test
    fun `test ListRegistry varargs constructor`() {
        val registry = ListRegistry("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), registry)

        registry.register("d")
        assertEquals(listOf("a", "b", "c", "d"), registry)
    }

    @Test
    fun `test ListRegistry varargs constructor with no items`() {
        val registry = ListRegistry<String>()
        assertTrue(registry.isEmpty())
    }

    // by Claude - Tests for List interface methods
    @Test
    fun `test List interface methods work correctly`() {
        val registry = ListRegistry("a", "b", "c")

        // size
        assertEquals(3, registry.size)

        // get
        assertEquals("a", registry[0])
        assertEquals("b", registry[1])
        assertEquals("c", registry[2])

        // contains
        assertTrue(registry.contains("a"))
        assertTrue(!registry.contains("z"))

        // containsAll
        assertTrue(registry.containsAll(listOf("a", "b")))
        assertTrue(!registry.containsAll(listOf("a", "z")))

        // indexOf
        assertEquals(0, registry.indexOf("a"))
        assertEquals(-1, registry.indexOf("z"))

        // lastIndexOf
        val registryWithDupes = ListRegistry("a", "b", "a")
        assertEquals(2, registryWithDupes.lastIndexOf("a"))

        // isEmpty
        assertTrue(!registry.isEmpty())
        assertTrue(ListRegistry<String>().isEmpty())

        // iterator
        val iterated = mutableListOf<String>()
        for (item in registry) {
            iterated.add(item)
        }
        assertEquals(listOf("a", "b", "c"), iterated)

        // subList
        assertEquals(listOf("b", "c"), registry.subList(1, 3))
    }

    // by Claude - Test include with empty list
    @Test
    fun `test include with empty list does nothing`() {
        val registry = ListRegistry("a")
        registry.include(emptyList())
        assertEquals(listOf("a"), registry)
    }

    // by Claude - Test buildListRegistry with no items
    @Test
    fun `test buildListRegistry with no items creates empty list`() {
        val list = buildListRegistry<String> { }
        assertTrue(list.isEmpty())
    }
}
