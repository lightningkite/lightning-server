package com.lightningkite.lightningserver.definition.builder

import kotlin.test.*

class MapRegistryTest {

    @Test
    fun `test register adds entries`() {
        val registry = MapRegistry<String, Int>()
        assertTrue(registry.isEmpty())

        registry.register("a", 1)
        assertEquals(1, registry["a"])
        assertEquals(1, registry.size)

        registry.register("b", 2)
        assertEquals(2, registry["b"])
        assertEquals(2, registry.size)
    }

    @Test
    fun `test duplicate registration throws error`() {
        val registry = MapRegistry<String, Int>()
        registry.register("a", 1)

        val error = assertFailsWith<DuplicateRegistrationException> {
            registry.register("a", 2)
        }

        assertEquals(1, error.initial)
        assertEquals(2, error.overwrite)
        assertTrue(error.message!!.contains("already has a registered value"))
    }

    @Test
    fun `test include adds multiple entries`() {
        val registry = MapRegistry<String, Int>()
        registry.include(mapOf("a" to 1, "b" to 2, "c" to 3))

        assertEquals(3, registry.size)
        assertEquals(1, registry["a"])
        assertEquals(2, registry["b"])
        assertEquals(3, registry["c"])
    }

    @Test
    fun `test include detects duplicates`() {
        val registry = MapRegistry<String, Int>()
        registry.register("a", 1)

        assertFailsWith<DuplicateRegistrationException> {
            registry.include(mapOf("a" to 2, "b" to 3))
        }
    }

    @Test
    fun `test getOrRegister creates default if absent`() {
        val registry = MapRegistry<String, Int>()
        var computeCount = 0

        val value1 = registry.getOrRegister("a") {
            computeCount++
            42
        }

        assertEquals(42, value1)
        assertEquals(1, computeCount)
        assertEquals(42, registry["a"])
    }

    @Test
    fun `test getOrRegister returns existing value`() {
        val registry = MapRegistry<String, Int>()
        registry.register("a", 1)

        var computeCount = 0
        val value = registry.getOrRegister("a") {
            computeCount++
            42
        }

        assertEquals(1, value) // Should return existing value
        assertEquals(0, computeCount) // Should not compute
    }

    @Test
    fun `test buildMapRegistry creates immutable map`() {
        val map = buildMapRegistry<String, Int> {
            register("a", 1)
            register("b", 2)
        }

        assertEquals(mapOf("a" to 1, "b" to 2), map)
    }

    @Test
    fun `test registry preserves insertion order`() {
        val registry = MapRegistry<Int, String>()
        registry.register(3, "three")
        registry.register(1, "one")
        registry.register(2, "two")

        assertEquals(listOf(3, 1, 2), registry.keys.toList())
    }
}
