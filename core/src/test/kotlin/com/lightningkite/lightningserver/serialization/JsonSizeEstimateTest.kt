package com.lightningkite.lightningserver.serialization

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class Simple(val id: String, val count: Int, val enabled: Boolean, val note: String? = null)

@Serializable
private sealed interface Shape {
    @Serializable
    @SerialName("circle")
    data class Circle(val radius: Double) : Shape

    @Serializable
    @SerialName("rect")
    data class Rect(val width: Double, val height: Double) : Shape
}

@Serializable
private data class HasShape(val id: String, val shape: Shape)

/** A self-referential model: fatal to anything that walks descriptors instead of values. */
@Serializable
private data class Node(val name: String, val children: List<Node> = emptyList())

class JsonSizeEstimateTest {

    private val serialization = Serialization()

    private fun <T> estimate(serializer: kotlinx.serialization.KSerializer<T>, value: T, limit: Int = Int.MAX_VALUE) =
        serialization.approximateJsonSize(serializer, value, limit)

    private fun <T> actual(serializer: kotlinx.serialization.KSerializer<T>, value: T) =
        serialization.json.encodeToString(serializer, value).length

    /** Within a factor of two of the real thing is all the overload decision needs. */
    private fun <T> assertCloseToActual(serializer: kotlinx.serialization.KSerializer<T>, value: T) {
        val estimated = estimate(serializer, value)
        val real = actual(serializer, value)
        assertTrue(
            estimated >= real / 2 && estimated <= real * 2,
            "estimate $estimated is not within 2x of actual $real"
        )
    }

    @Test
    fun simple_object_is_close_to_actual() =
        assertCloseToActual(Simple.serializer(), Simple("abc", 42, true, "hello"))

    @Test
    fun nulls_are_counted() = assertCloseToActual(Simple.serializer(), Simple("abc", 42, false))

    @Test
    fun lists_are_counted() = assertCloseToActual(
        ListSerializer(Simple.serializer()),
        (1..50).map { Simple("id$it", it, it % 2 == 0, "note $it") }
    )

    /**
     * The reason this measures values rather than descriptors. A descriptor-based estimate assumes a
     * fixed size per string, so a handful of very large fields slips under any threshold.
     */
    @Test
    fun long_strings_are_counted_at_their_real_length() {
        val small = estimate(Simple.serializer(), Simple("a", 1, true, "x"))
        val large = estimate(Simple.serializer(), Simple("a", 1, true, "x".repeat(50_000)))
        assertTrue(large > 50_000, "a 50k string must dominate the estimate, got $large")
        assertTrue(large > small * 100)
        assertCloseToActual(Simple.serializer(), Simple("a", 1, true, "x".repeat(50_000)))
    }

    /**
     * Regression test: the previous descriptor-walking estimate hit `TODO()` on polymorphic kinds and
     * threw NotImplementedError. Sealed members are common in real models, and the throw landed in a
     * subscription coroutine with no handler, silently deadening the connection.
     */
    @Test
    fun sealed_hierarchies_do_not_throw() {
        assertCloseToActual(HasShape.serializer(), HasShape("a", Shape.Circle(1.5)))
        assertCloseToActual(HasShape.serializer(), HasShape("b", Shape.Rect(2.0, 3.0)))
    }

    /** Recursion is bounded by the data, not by the type, so a self-referential model is fine. */
    @Test
    fun recursive_models_terminate() {
        val tree = Node("root", listOf(Node("a", listOf(Node("a1"))), Node("b")))
        assertCloseToActual(Node.serializer(), tree)
    }

    @Test
    fun measurement_stops_once_the_limit_is_reached() {
        val huge = (1..100_000).map { Simple("id$it", it, true, "note $it") }
        val limited = estimate(ListSerializer(Simple.serializer()), huge, limit = 24_000)
        assertTrue(limited >= 24_000, "should report at least the limit, got $limited")
        // Stopping early means it must not have walked the whole list.
        assertTrue(limited < 100_000, "should have abandoned measurement near the limit, got $limited")
    }

    @Test
    fun a_value_under_the_limit_is_measured_exactly_as_if_unlimited() {
        val value = Simple("abc", 42, true, "hello")
        assertEquals(estimate(Simple.serializer(), value), estimate(Simple.serializer(), value, limit = 24_000))
    }
}
