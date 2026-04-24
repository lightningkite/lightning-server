package com.lightningkite.lightningserver.auth

import kotlin.test.*

/**
 * Tests for edge cases and boundary conditions in scope handling.
 */
class EdgeCaseTests {

    @Test
    fun emptyStringScopes() {
        // Edge case: empty string scopes (not recommended but should work consistently)
        val emptyRequired = RequiredScope("")
        val emptyGranted = GrantedScope("")

        assertTrue(emptyGranted.meetsRequirements(emptyRequired))
        assertTrue(emptyGranted.meetsRequirements(RequiredScope(":sub")))
    }

    @Test
    fun deeplyNestedScopes() {
        // Test deeply nested scope hierarchies
        val deep = "a:b:c:d:e:f:g:h:i:j"
        val deepRequired = RequiredScope(deep)
        val deepGranted = GrantedScope(deep)

        assertTrue(deepGranted.meetsRequirements(deepRequired))
        assertTrue(GrantedScope("a").meetsRequirements(deepRequired))
        assertFalse(GrantedScope("a:b:c:d:e:f:g:h:i:j:k").meetsRequirements(RequiredScope("a")))
    }

    @Test
    fun similarButNotMatchingScopes() {
        // Test scopes that look similar but shouldn't match
        assertFalse(GrantedScope("admin").meetsRequirements(RequiredScope("administrator")))
        assertFalse(GrantedScope("user").meetsRequirements(RequiredScope("users")))
        assertFalse(GrantedScope("read").meetsRequirements(RequiredScope("readonly")))

        // Partial substring matches should not work
        assertFalse(GrantedScope("adm").meetsRequirements(RequiredScope("admin")))
    }

    @Test
    fun caseSensitivity() {
        // Scopes should be case-sensitive
        val lowerGranted = GrantedScope("admin")
        val upperRequired = RequiredScope("ADMIN")

        assertFalse(lowerGranted.meetsRequirements(upperRequired))

        val mixedGranted = GrantedScope("Admin:Users")
        val lowerRequired = RequiredScope("admin:users")

        assertFalse(mixedGranted.meetsRequirements(lowerRequired))
    }

    @Test
    fun subscope_withSpecialCharacters() {
        // Test subscopes with various characters
        val subscope = Subscope("user-management")
        val scope = RequiredScope("admin").subscope(subscope)

        assertEquals("admin:user-management", scope.asString)

        // Test combining multiple subscopes with special chars
        val sub1 = Subscope("foo_bar")
        val sub2 = Subscope("baz-qux")
        val combined = sub1 + sub2

        assertEquals("foo_bar:baz-qux", combined.asString)
    }

    @Test
    fun simplify_withRootScope() {
        // When root is present, everything else should be removed
        val scopes = listOf(
            RequiredScope.root,
            RequiredScope("admin"),
            RequiredScope("user"),
            RequiredScope("admin:users")
        )

        val simplified = scopes.simplify()

        assertEquals(setOf(RequiredScope.root), simplified)
    }

    @Test
    fun simplify_preservesUnrelatedBranches() {
        // Scopes from different branches should all be preserved
        val scopes = listOf(
            RequiredScope("admin:users"),
            RequiredScope("admin:posts"),
            RequiredScope("public:read"),
            RequiredScope("public:comments"),
            RequiredScope("api:v1")
        )

        val simplified = scopes.simplify()

        // All are unrelated, so all should remain
        assertEquals(5, simplified.size)
        assertTrue(simplified.containsAll(scopes))
    }

    @Test
    fun simplify_emptyList() {
        val simplified = emptyList<RequiredScope>().simplify()
        assertTrue(simplified.isEmpty())
    }

    @Test
    fun simplify_singleElement() {
        val scopes = listOf(RequiredScope("admin"))
        val simplified = scopes.simplify()

        assertEquals(setOf(RequiredScope("admin")), simplified)
    }

    @Test
    fun subscope_multipleSubscopes() {
        // Test applying multiple subscopes to a set
        val baseScopes = setOf(RequiredScope("api"), RequiredScope("admin"))
        val subs = listOf(Subscope("v1"), Subscope("v2"))

        val result = baseScopes.subscope(subs)

        assertEquals(4, result.size)
        assertTrue(result.contains(RequiredScope("api:v1")))
        assertTrue(result.contains(RequiredScope("api:v2")))
        assertTrue(result.contains(RequiredScope("admin:v1")))
        assertTrue(result.contains(RequiredScope("admin:v2")))
    }

    @Test
    fun restrict_multipleSubscopes() {
        // Test applying multiple subscopes to granted scopes
        val baseGrants = setOf(GrantedScope("admin"))
        val subs = listOf(Subscope("read"), Subscope("write"), Subscope("delete"))

        val result = baseGrants.restrict(subs)

        assertEquals(3, result.size)
        assertTrue(result.contains(GrantedScope("admin:read")))
        assertTrue(result.contains(GrantedScope("admin:write")))
        assertTrue(result.contains(GrantedScope("admin:delete")))
    }

    @Test
    fun meetsRequirements_emptyGrantedSet() {
        // Empty granted set cannot meet any requirements
        val emptyGrants = emptySet<GrantedScope>()
        assertFalse(emptyGrants.meetsRequirements(setOf(RequiredScope("anything"))))
    }

    @Test
    fun meetsRequirements_emptyRequiredSet() {
        // Empty required set is trivially satisfied
        val grants = setOf(GrantedScope("admin"))
        assertTrue(grants.meetsRequirements(emptySet()))

        // Even empty granted set meets empty requirements
        assertTrue(emptySet<GrantedScope>().meetsRequirements(emptySet()))
    }

    @Test
    fun meetsRequirements_complexMultipleGrants() {
        // Test multiple grants meeting multiple requirements
        val grants = setOf(
            GrantedScope("admin:users"),
            GrantedScope("admin:posts"),
            GrantedScope("public:read")
        )

        // Should meet these requirements
        assertTrue(
            grants.meetsRequirements(
                setOf(
                    RequiredScope("admin:users:list"),
                    RequiredScope("admin:posts:create"),
                    RequiredScope("public:read")
                )
            )
        )

        // Should NOT meet these requirements (missing admin root)
        assertFalse(
            grants.meetsRequirements(
                setOf(
                    RequiredScope("admin") // too broad, grants are more specific
                )
            )
        )
    }

    @Test
    fun contains_identicalScopes() {
        // A scope should contain itself
        val scope = RequiredScope("admin:users")
        assertTrue(scope in scope)

        val root = RequiredScope.root
        assertTrue(root in root)
    }

    @Test
    fun rootScope_asymmetry() {
        // Document and test the asymmetric behavior of root scope

        // Granted root meets any non-root requirement
        assertTrue(GrantedScope.root.meetsRequirements(RequiredScope("anything")))
        assertTrue(GrantedScope.root.meetsRequirements(RequiredScope("a:b:c:d")))

        // But granted root CAN meet root requirement
        assertTrue(GrantedScope.root.meetsRequirements(RequiredScope.root))

        // Non-root grant cannot meet root requirement
        assertFalse(GrantedScope("admin").meetsRequirements(RequiredScope.root))
        assertFalse(GrantedScope("a:b:c").meetsRequirements(RequiredScope.root))
    }
}
