// by Claude
package com.lightningkite.lightningserver.typed.jsonschema

import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for JSON merge utility functions in Util.kt.
 * These functions are used internally for merging JSON schemas.
 */
class UtilTest {

    // ========== merge(JsonElement, JsonElement) Tests ==========

    @Test
    fun `merge identical primitive values returns the value`() {
        val a = JsonPrimitive("test")
        val b = JsonPrimitive("test")

        val result = merge(a, b)

        assertEquals(JsonPrimitive("test"), result)
    }

    @Test
    fun `merge different primitive values returns the new value`() {
        val a = JsonPrimitive("old")
        val b = JsonPrimitive("new")

        val result = merge(a, b)

        assertEquals(JsonPrimitive("new"), result)
    }

    @Test
    fun `merge with JsonNull returns previous value`() {
        val a = JsonPrimitive("keep")
        val b = JsonNull

        val result = merge(a, b)

        assertEquals(JsonPrimitive("keep"), result)
    }

    @Test
    fun `merge JsonNull with value returns the value`() {
        val a = JsonNull
        val b = JsonPrimitive("new")

        val result = merge(a, b)

        assertEquals(JsonPrimitive("new"), result)
    }

    @Test
    fun `merge identical JsonNull returns JsonNull`() {
        val result = merge(JsonNull, JsonNull)

        assertEquals(JsonNull, result)
    }

    // ========== merge(JsonArray, JsonArray) Tests ==========

    @Test
    fun `merge two arrays concatenates them`() {
        val a = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))
        val b = JsonArray(listOf(JsonPrimitive(3), JsonPrimitive(4)))

        val result = merge(a, b)

        val expected = JsonArray(listOf(
            JsonPrimitive(1),
            JsonPrimitive(2),
            JsonPrimitive(3),
            JsonPrimitive(4)
        ))
        assertEquals(expected, result)
    }

    @Test
    fun `merge empty arrays returns empty array`() {
        val a = JsonArray(emptyList())
        val b = JsonArray(emptyList())

        val result = merge(a, b)

        assertEquals(JsonArray(emptyList()), result)
    }

    @Test
    fun `merge array with empty array returns first array`() {
        val a = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))
        val b = JsonArray(emptyList())

        val result = merge(a, b)

        assertEquals(a, result)
    }

    @Test
    fun `merge empty array with array returns second array`() {
        val a = JsonArray(emptyList())
        val b = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))

        val result = merge(a, b)

        assertEquals(b, result)
    }

    // ========== merge(JsonObject, JsonObject) Tests ==========

    @Test
    fun `merge objects with disjoint keys combines all keys`() {
        val a = buildJsonObject { put("a", 1) }
        val b = buildJsonObject { put("b", 2) }

        val result = merge(a, b)

        val expected = buildJsonObject {
            put("a", 1)
            put("b", 2)
        }
        assertEquals(expected, result)
    }

    @Test
    fun `merge objects with same key and same value keeps value`() {
        val a = buildJsonObject { put("key", "value") }
        val b = buildJsonObject { put("key", "value") }

        val result = merge(a, b)

        assertEquals(buildJsonObject { put("key", "value") }, result)
    }

    @Test
    fun `merge objects with same key different values takes new value`() {
        val a = buildJsonObject { put("key", "old") }
        val b = buildJsonObject { put("key", "new") }

        val result = merge(a, b)

        assertEquals(buildJsonObject { put("key", "new") }, result)
    }

    @Test
    fun `merge nested objects recursively merges`() {
        val a = buildJsonObject {
            putJsonObject("nested") {
                put("a", 1)
            }
        }
        val b = buildJsonObject {
            putJsonObject("nested") {
                put("b", 2)
            }
        }

        val result = merge(a, b)

        val expected = buildJsonObject {
            putJsonObject("nested") {
                put("a", 1)
                put("b", 2)
            }
        }
        assertEquals(expected, result)
    }

    @Test
    fun `merge object with JsonNull key preserves existing value`() {
        val a = buildJsonObject { put("key", "value") }
        val b = buildJsonObject { put("key", JsonNull) }

        val result = merge(a, b)

        assertEquals(buildJsonObject { put("key", "value") }, result)
    }

    @Test
    fun `merge empty objects returns empty object`() {
        val a = buildJsonObject { }
        val b = buildJsonObject { }

        val result = merge(a, b)

        assertEquals(buildJsonObject { }, result)
    }

    // ========== MutableMap.merge Tests ==========

    @Test
    fun `map merge adds new key`() {
        val map = mutableMapOf<String, JsonElement>()

        map.merge("key", JsonPrimitive("value"))

        assertEquals(JsonPrimitive("value"), map["key"])
    }

    @Test
    fun `map merge updates existing key`() {
        val map = mutableMapOf<String, JsonElement>("key" to JsonPrimitive("old"))

        map.merge("key", JsonPrimitive("new"))

        assertEquals(JsonPrimitive("new"), map["key"])
    }

    @Test
    fun `map merge with JsonNull keeps existing value`() {
        val map = mutableMapOf<String, JsonElement>("key" to JsonPrimitive("keep"))

        map.merge("key", JsonNull)

        assertEquals(JsonPrimitive("keep"), map["key"])
    }

    @Test
    fun `map merge with object values merges nested content`() {
        val map = mutableMapOf<String, JsonElement>(
            "key" to buildJsonObject { put("a", 1) }
        )

        map.merge("key", buildJsonObject { put("b", 2) })

        val expected = buildJsonObject {
            put("a", 1)
            put("b", 2)
        }
        assertEquals(expected, map["key"])
    }

    // ========== Edge Cases ==========

    @Test
    fun `merge boolean primitives`() {
        val result = merge(JsonPrimitive(true), JsonPrimitive(false))
        assertEquals(JsonPrimitive(false), result)
    }

    @Test
    fun `merge number primitives`() {
        val result = merge(JsonPrimitive(42), JsonPrimitive(100))
        assertEquals(JsonPrimitive(100), result)
    }

    @Test
    fun `merge deeply nested objects`() {
        val a = buildJsonObject {
            putJsonObject("level1") {
                putJsonObject("level2") {
                    put("a", 1)
                }
            }
        }
        val b = buildJsonObject {
            putJsonObject("level1") {
                putJsonObject("level2") {
                    put("b", 2)
                }
            }
        }

        val result = merge(a, b)

        val expected = buildJsonObject {
            putJsonObject("level1") {
                putJsonObject("level2") {
                    put("a", 1)
                    put("b", 2)
                }
            }
        }
        assertEquals(expected, result)
    }

    @Test
    fun `merge mixed types in array`() {
        val a = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("text")))
        val b = JsonArray(listOf(JsonPrimitive(true), JsonNull))

        val result = merge(a, b)

        val expected = JsonArray(listOf(
            JsonPrimitive(1),
            JsonPrimitive("text"),
            JsonPrimitive(true),
            JsonNull
        ))
        assertEquals(expected, result)
    }

    @Test
    fun `merge object containing arrays`() {
        val a = buildJsonObject {
            putJsonArray("items") {
                add(1)
                add(2)
            }
        }
        val b = buildJsonObject {
            putJsonArray("items") {
                add(3)
                add(4)
            }
        }

        val result = merge(a, b)

        val expected = buildJsonObject {
            putJsonArray("items") {
                add(1)
                add(2)
                add(3)
                add(4)
            }
        }
        assertEquals(expected, result)
    }
}
