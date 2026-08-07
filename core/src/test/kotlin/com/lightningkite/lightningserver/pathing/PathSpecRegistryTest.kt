// by Claude
package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.definition.builder.DuplicateRegistrationException
import com.lightningkite.services.data.StringArrayFormat
import kotlinx.serialization.modules.EmptySerializersModule
import org.junit.Test
import kotlin.test.*

/**
 * Tests for PathSpecRegistry functionality including registration, duplicate detection,
 * and the buildPathSpecRegistry builder function.
 */
class PathSpecRegistryTest {

    private val saf = StringArrayFormat(EmptySerializersModule())

    @Test
    fun `register adds value at path`() {
        val registry = PathSpecRegistry<String>()

        registry.register(PathSpec.root, "root-value")

        assertEquals("root-value", registry[PathSpec.root])
    }

    @Test
    fun `register allows multiple different paths`() {
        val registry = PathSpecRegistry<String>()

        registry.register(PathSpec.root, "root")
        registry.register(PathSpec.root.path("users"), "users")
        registry.register(PathSpec.root.path("posts"), "posts")

        assertEquals("root", registry[PathSpec.root])
        assertEquals("users", registry[PathSpec.root.path("users")])
        assertEquals("posts", registry[PathSpec.root.path("posts")])
    }

    @Test
    fun `register throws DuplicateRegistrationError on duplicate path`() {
        val registry = PathSpecRegistry<String>()

        registry.register(PathSpec.root, "first-value")

        val error = assertFailsWith<DuplicateRegistrationException> {
            registry.register(PathSpec.root, "second-value")
        }

        assertEquals("first-value", error.initial)
        assertEquals("second-value", error.overwrite)
    }

    @Test
    fun `register throws DuplicateRegistrationError on duplicate nested path`() {
        val registry = PathSpecRegistry<Int>()
        val path = PathSpec.root.path("api").path("v1")

        registry.register(path, 100)

        val error = assertFailsWith<DuplicateRegistrationException> {
            registry.register(path, 200)
        }

        assertEquals(100, error.initial)
        assertEquals(200, error.overwrite)
    }

    @Test
    fun `register works with wildcard paths`() {
        val registry = PathSpecRegistry<String>()

        registry.register(PathSpec.root.arg<String>("id"), "wildcard-handler")
        registry.register(PathSpec.root.path("users").arg<Int>("userId"), "user-handler")

        assertEquals("wildcard-handler", registry[PathSpec.root.arg<String>("id")])
        assertEquals("user-handler", registry[PathSpec.root.path("users").arg<Int>("userId")])
    }

    @Test
    fun `buildPathSpecRegistry creates sealed registry`() {
        val registry = buildPathSpecRegistry<String> {
            register(PathSpec.root, "root")
            register(PathSpec.root.path("api"), "api")
        }

        assertEquals("root", registry[PathSpec.root])
        assertEquals("api", registry[PathSpec.root.path("api")])
    }

    @Test
    fun `buildPathSpecRegistry returns immutable map`() {
        val registry = buildPathSpecRegistry<String> {
            register(PathSpec.root, "value")
        }

        // The returned map should be a sealed (immutable) PathSpecMap
        assertNotNull(registry[PathSpec.root])
    }

    @Test
    fun `registry asSequence returns all entries`() {
        val registry = PathSpecRegistry<String>()

        registry.register(PathSpec.root, "root")
        registry.register(PathSpec.root.path("a"), "a")
        registry.register(PathSpec.root.path("b"), "b")

        val entries = registry.asSequence().toList()

        assertEquals(3, entries.size)
        assertEquals(setOf("root", "a", "b"), entries.map { it.value }.toSet())
    }

    @Test
    fun `registry match works correctly`() {
        val registry = PathSpecRegistry<String>()

        registry.register(PathSpec.root, "root")
        registry.register(PathSpec.root.path("users"), "users")

        val rootMatch = registry.match(saf, "/") { it }
        val usersMatch = registry.match(saf, "users") { it }

        assertNotNull(rootMatch)
        assertEquals("root", rootMatch.value)

        assertNotNull(usersMatch)
        assertEquals("users", usersMatch.value)
    }

    @Test
    fun `registry returns null for non-existent path`() {
        val registry = PathSpecRegistry<String>()

        registry.register(PathSpec.root, "root")

        assertNull(registry[PathSpec.root.path("nonexistent")])
    }

    @Test
    fun `registry works with trailing slash paths`() {
        val registry = PathSpecRegistry<String>()

        registry.register(PathSpec.root.path("api").slash, "api-trailing")

        assertEquals("api-trailing", registry[PathSpec.root.path("api").slash])

        val match = registry.match(saf, "api/") { it }
        assertNotNull(match)
        assertEquals("api-trailing", match.value)
    }

    @Test
    fun `registry works with any wildcard`() {
        val registry = PathSpecRegistry<String>()

        registry.register(PathSpec.root.any, "catch-all")

        assertEquals("catch-all", registry[PathSpec.root.any])
    }
}
