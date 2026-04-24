package com.lightningkite.lightningserver.definition

import kotlin.test.Test
import kotlin.test.assertEquals

class LocationedTest {

    @Test
    fun `test Locationed stores location and item`() {
        val locationed = Locationed("path/to/resource", "item value")

        assertEquals("path/to/resource", locationed.location)
        assertEquals("item value", locationed.item)
    }

    @Test
    fun `test Locationed implements Map Entry`() {
        val locationed = Locationed("key", "value")

        assertEquals("key", locationed.key)
        assertEquals("value", locationed.value)
    }

    @Test
    fun `test mapItems transforms items preserving locations`() {
        val list = listOf(
            Locationed("/a", 1),
            Locationed("/b", 2),
            Locationed("/c", 3)
        )

        val transformed = list.mapItems { it * 10 }

        assertEquals(3, transformed.size)
        assertEquals(Locationed("/a", 10), transformed[0])
        assertEquals(Locationed("/b", 20), transformed[1])
        assertEquals(Locationed("/c", 30), transformed[2])
    }

    @Test
    fun `test mapItems with type transformation`() {
        val list = listOf(
            Locationed(1, "one"),
            Locationed(2, "two"),
            Locationed(3, "three")
        )

        val transformed = list.mapItems { it.length }

        assertEquals(
            listOf(
                Locationed(1, 3),
                Locationed(2, 3),
                Locationed(3, 5)
            ), transformed
        )
    }
}
