// by Claude
package com.lightningkite.lightningserver.pathing

import com.lightningkite.lightningserver.http.PathSegments
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for PathSpec and related classes.
 */
class PathSpecExtensionsTest {

    // ========== PathSpec.Segment Tests ==========

    @Test
    fun `Segment Constant creation`() {
        val constant = PathSpec.Segment.Constant("users")
        assertEquals("users", constant.value)
        assertEquals("users", constant.toString())
    }

    @Test
    fun `Segment Constant disallows slashes`() {
        try {
            PathSpec.Segment.Constant("users/posts")
            assertTrue(false, "Should have thrown exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("slash") == true)
        }
    }

    @Test
    fun `Segment Empty constant`() {
        val empty = PathSpec.Segment.Empty
        assertEquals("", empty.value)
    }

    @Test
    fun `Segment Wildcard toString`() {
        val wildcard = PathSpec.Segment.Wildcard("userId", String.serializer())
        assertEquals("{userId}", wildcard.toString())
    }

    @Test
    fun `Segment fromString parses constants`() {
        val segments = PathSpec.Segment.fromString("/users/posts")
        assertEquals(2, segments.size)
        assertTrue(segments[0] is PathSpec.Segment.Constant)
        assertEquals("users", (segments[0] as PathSpec.Segment.Constant).value)
        assertTrue(segments[1] is PathSpec.Segment.Constant)
        assertEquals("posts", (segments[1] as PathSpec.Segment.Constant).value)
    }

    @Test
    fun `Segment fromString parses wildcards`() {
        val segments = PathSpec.Segment.fromString("/users/{id}")
        assertEquals(2, segments.size)
        assertTrue(segments[0] is PathSpec.Segment.Constant)
        assertTrue(segments[1] is PathSpec.Segment.Wildcard<*>)
        assertEquals("id", (segments[1] as PathSpec.Segment.Wildcard<*>).name)
    }

    @Test
    fun `Segment fromString ignores trailing segments marker`() {
        val segments = PathSpec.Segment.fromString("/users/{...}")
        assertEquals(1, segments.size)
        assertEquals("users", (segments[0] as PathSpec.Segment.Constant).value)
    }

    // ========== PathSpec.Afterwards Tests ==========

    @Test
    fun `Afterwards None value`() {
        assertEquals(PathSpec.Afterwards.None, PathSpec.Afterwards.fromString("/users"))
    }

    @Test
    fun `Afterwards TrailingSegments detected`() {
        assertEquals(PathSpec.Afterwards.TrailingSegments, PathSpec.Afterwards.fromString("/users/{...}"))
    }

    // ========== PathSpec0 Tests ==========

    @Test
    fun `PathSpec0 creation and toString`() {
        val spec = PathSpec0(listOf(PathSpec.Segment.Constant("api")), PathSpec.Afterwards.None)
        assertEquals("/api", spec.toString())
    }

    @Test
    fun `PathSpec0 fromString`() {
        val spec = PathSpec0.fromString("/api/v1")
        assertEquals("/api/v1", spec.toString())
    }

    @Test
    fun `PathSpec0 with trailing segments using any property`() {
        val spec = PathSpec0.fromString("/api").any
        assertEquals("/api/{...}", spec.toString())
        assertEquals(PathSpec.Afterwards.TrailingSegments, spec.after)
    }

    @Test
    fun `PathSpec0 path appends constant`() {
        val spec = PathSpec0.fromString("/api")
        val extended = spec.path("users")
        assertEquals("/api/users", extended.toString())
    }

    @Test
    fun `PathSpec0 has empty wildcards list`() {
        val spec = PathSpec0.fromString("/api")
        assertTrue(spec.wildcards.isEmpty())
    }

    @Test
    fun `PathSpec root has null parent`() {
        val spec = PathSpec.root
        assertNull(spec.parent)
        assertTrue(spec.segments.isEmpty())
    }

    @Test
    fun `PathSpec0 parent is correct`() {
        val spec = PathSpec0.fromString("/api/users")
        val parent = spec.parent
        assertNotNull(parent)
        assertEquals("/api", parent.toString())
    }

    // ========== PathSpec equality and hashCode Tests ==========

    @Test
    fun `PathSpec equals same path`() {
        val spec1 = PathSpec0.fromString("/api/users")
        val spec2 = PathSpec0.fromString("/api/users")
        assertEquals(spec1, spec2)
    }

    @Test
    fun `PathSpec equals different path`() {
        val spec1 = PathSpec0.fromString("/api/users")
        val spec2 = PathSpec0.fromString("/api/posts")
        assertFalse(spec1 == spec2)
    }

    @Test
    fun `PathSpec hashCode same for equal paths`() {
        val spec1 = PathSpec0.fromString("/api/users")
        val spec2 = PathSpec0.fromString("/api/users")
        assertEquals(spec1.hashCode(), spec2.hashCode())
    }

    // ========== PathSpec with wildcards Tests ==========

    @Test
    fun `PathSpec1 has one wildcard`() {
        val spec = PathSpec0.fromString("/users").arg<String>("id")
        assertTrue(spec is PathSpec1<*>)
        assertEquals(1, spec.wildcards.size)
        assertEquals("id", spec.wildcards[0].name)
    }

    @Test
    fun `PathSpec2 has two wildcards`() {
        val spec = PathSpec0.fromString("/users")
            .arg<String>("userId")
            .path("posts")
            .arg<Int>("postId")
        assertTrue(spec is PathSpec2<*, *>)
        assertEquals(2, spec.wildcards.size)
    }

    @Test
    fun `PathSpec3 has three wildcards`() {
        val spec = PathSpec0.fromString("/users")
            .arg<String>("userId")
            .path("posts")
            .arg<Int>("postId")
            .path("comments")
            .arg<Long>("commentId")
        assertTrue(spec is PathSpec3<*, *, *>)
        assertEquals(3, spec.wildcards.size)
    }

}
