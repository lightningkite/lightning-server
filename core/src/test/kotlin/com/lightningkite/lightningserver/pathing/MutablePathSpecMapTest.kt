package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.http.PathSegments
import com.lightningkite.services.data.StringArrayFormat
import kotlinx.serialization.modules.EmptySerializersModule
import org.junit.Test
import kotlin.test.assertEquals

class MutablePathSpecMapTest {

    fun Any?.kotlin(): String = when (this) {
        null -> "null"
        is String -> "\"$this\""
        is Int -> "$this"
        else -> this::class.qualifiedName ?: "unknown"
    }

    @Test
    fun withslash(): Unit {
        val saf = StringArrayFormat(EmptySerializersModule())
        val map = MutablePathSpecMap<Int>()

        map[PathSpec.root.path("variable").arg<String>("dumb")] = 42
        map.match(saf, "/variable/asdf") { it }?.let { println(it) }
        map.match(saf, "/variable/asdf%2fwithslash") { it }?.let { println(it) }
    }


    @Test
    fun pathSpecCheck(): Unit {
        val saf = StringArrayFormat(EmptySerializersModule())
        val map = MutablePathSpecMap<TestHoldingThing>()

        map[PathSpec.root] = TestHoldingThing(a = "root", b = 21)
        map[PathSpec.root.slash] = TestHoldingThing(a = "rootslash")
        map[PathSpec.root.any] = TestHoldingThing(a = "final fallback", b = 42)
        map[PathSpec.root.path("test")] = TestHoldingThing(a = "test")
        map[PathSpec.root.path("test").path("a")] = TestHoldingThing(a = "test-a")
        map[PathSpec.root.path("test").arg<Int>("id")] = TestHoldingThing(a = "test-x")
        val sealed = map.toSealedPathSpecMap()

        fun sample(path: String) {
            println("run {")
            val match = map.match(saf, path) { it.a }
            println("    val match = map.match(saf, \"$path\") { it.a }")
            println("    assertEquals(${match?.path?.let { "\"$it\"" }}, match?.path?.toString())")
            println("    assertEquals(listOf(${match?.path?.rawPathArguments?.joinToString(", ") { it.kotlin() }}), match?.path?.rawPathArguments?.toList())")
            println("}")
            println("run {")
            println("    val match = sealed.match(saf, \"$path\") { it.a }")
            println("    assertEquals(${match?.path?.let { "\"$it\"" }}, match?.path?.toString())")
            println("    assertEquals(listOf(${match?.path?.rawPathArguments?.joinToString(", ") { it.kotlin() }}), match?.path?.rawPathArguments?.toList())")
            println("}")
        }

        fun sampleInt(path: String) {
            println("run {")
            val match = map.match(saf, path) { it.b }
            println("    val match = map.match(saf, \"$path\") { it.b }")
            println("    assertEquals(${match?.path?.let { "\"$it\"" }}, match?.path?.toString())")
            println("    assertEquals(listOf(${match?.path?.rawPathArguments?.joinToString(", ") { it.kotlin() }}), match?.path?.rawPathArguments?.toList())")
            println("}")
            println("run {")
            println("    val match = sealed.match(saf, \"$path\") { it.b }")
            println("    assertEquals(${match?.path?.let { "\"$it\"" }}, match?.path?.toString())")
            println("    assertEquals(listOf(${match?.path?.rawPathArguments?.joinToString(", ") { it.kotlin() }}), match?.path?.rawPathArguments?.toList())")
            println("}")
        }
        sample("")
        sample("/")
        sample("weird")
        sample("test")
        sample("test/a")
        sample("test/22")
        sampleInt("test/a/b/c")
        sample("test/22/asdf")

        run {
            val match = map.match(saf, "") { it.a }
            assertEquals("/", match?.path?.toString())
            assertEquals(listOf(), match?.path?.rawPathArguments?.toList())
        }
        run {
            val match = sealed.match(saf, "") { it.a }
            assertEquals("/", match?.path?.toString())
            assertEquals(listOf(), match?.path?.rawPathArguments?.toList())
        }
        run {
            val match = map.match(saf, "/") { it.a }
            assertEquals("/", match?.path?.toString())
            assertEquals(listOf(), match?.path?.rawPathArguments?.toList())
        }
        run {
            val match = sealed.match(saf, "/") { it.a }
            assertEquals("/", match?.path?.toString())
            assertEquals(listOf(), match?.path?.rawPathArguments?.toList())
        }
        run {
            val match = map.match(saf, "weird") { it.a }
            assertEquals("/weird", match?.path?.toString())
            assertEquals(listOf(), match?.path?.rawPathArguments?.toList())
        }
        run {
            val match = sealed.match(saf, "weird") { it.a }
            assertEquals("/weird", match?.path?.toString())
            assertEquals(listOf(), match?.path?.rawPathArguments?.toList())
        }
        run {
            val match = map.match(saf, "test") { it.a }
            assertEquals("/test", match?.path?.toString())
            assertEquals(listOf(), match?.path?.rawPathArguments?.toList())
        }
        run {
            val match = sealed.match(saf, "test") { it.a }
            assertEquals("/test", match?.path?.toString())
            assertEquals(listOf(), match?.path?.rawPathArguments?.toList())
        }
        run {
            val match = map.match(saf, "test/a") { it.a }
            assertEquals("/test/a", match?.path?.toString())
            assertEquals(listOf(), match?.path?.rawPathArguments?.toList())
        }
        run {
            val match = sealed.match(saf, "test/a") { it.a }
            assertEquals("/test/a", match?.path?.toString())
            assertEquals(listOf(), match?.path?.rawPathArguments?.toList())
        }
        run {
            val match = map.match(saf, "test/22") { it.a }
            assertEquals("/test/{id=22}", match?.path?.toString())
            assertEquals(listOf(22), match?.path?.rawPathArguments?.toList())
        }
        run {
            val match = sealed.match(saf, "test/22") { it.a }
            assertEquals("/test/{id=22}", match?.path?.toString())
            assertEquals(listOf(22), match?.path?.rawPathArguments?.toList())
        }
        run {
            val match = map.match(saf, "test/a/b/c") { it.b }
            assertEquals("/test/a/b/c", match?.path?.toString())
            assertEquals(listOf(), match?.path?.rawPathArguments?.toList())
        }
        run {
            val match = sealed.match(saf, "test/a/b/c") { it.b }
            assertEquals("/test/a/b/c", match?.path?.toString())
            assertEquals(listOf(), match?.path?.rawPathArguments?.toList())
        }
        run {
            val match = map.match(saf, "test/22/asdf") { it.a }
            assertEquals("/test/22/asdf", match?.path?.toString())
            assertEquals(listOf(), match?.path?.rawPathArguments?.toList())
        }
        run {
            val match = sealed.match(saf, "test/22/asdf") { it.a }
            assertEquals("/test/22/asdf", match?.path?.toString())
            assertEquals(listOf(), match?.path?.rawPathArguments?.toList())
        }
    }
}

private data class TestHoldingThing(val a: String? = null, val b: Int? = null) {}