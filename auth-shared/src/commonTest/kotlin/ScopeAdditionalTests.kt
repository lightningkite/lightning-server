package com.lightningkite.lightningserver.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopeAdditionalTests {

    @Test
    fun rootBehavior() {
        // Granted root meets all requirements
        assertTrue(GrantedScope.root.meetsRequirements(RequiredScope("*")))
        assertTrue(GrantedScope.root.meetsRequirements(RequiredScope("a")))
        assertTrue(GrantedScope.root.meetsRequirements(RequiredScope("a:b:c")))

        // Required root is only met by granted root
        assertFalse(GrantedScope("a").meetsRequirements(RequiredScope("*")))
        assertFalse(GrantedScope("a:b").meetsRequirements(RequiredScope("*")))

        // Set variant
        assertTrue(setOf(GrantedScope.root).meetsRequirements(setOf(RequiredScope.root)))
        assertTrue(setOf(GrantedScope.root).meetsRequirements(setOf(RequiredScope("x"))))
        assertFalse(setOf(GrantedScope("x")).meetsRequirements(setOf(RequiredScope.root)))
    }

    @Test
    fun restrictBehavior() {
        // Restrict from root replaces with the sub
        assertEquals(GrantedScope("a"), GrantedScope.root.restrict(Subscope("a")))
        assertEquals(GrantedScope("a:b"), GrantedScope("a").restrict(Subscope("b")))
        assertEquals(GrantedScope("a:b:c"), GrantedScope("a:b").restrict(Subscope("c")))
    }

    @Test
    fun subscopeBehavior() {
        // subscope from root drops to the provided sub only
        assertEquals(RequiredScope("a"), RequiredScope.root.subscope(Subscope("a")))
        assertEquals(RequiredScope("a:b"), RequiredScope("a").subscope(Subscope("b")))
        assertEquals(RequiredScope("a:b:c"), RequiredScope("a:b").subscope(Subscope("c")))
    }

    @Test
    fun meetsRequirements_setEdgeCases() {
        // Empty required set should be trivially met
        assertTrue(setOf(GrantedScope("a")).meetsRequirements(emptySet()))
        assertTrue(emptySet<GrantedScope>().meetsRequirements(emptySet()))

        // Empty granted set cannot meet any non-empty requirements
        assertFalse(emptySet<GrantedScope>().meetsRequirements(setOf(RequiredScope("a"))))

        // Duplicates should not affect outcome
        assertTrue(setOf(GrantedScope("a"), GrantedScope("a")).meetsRequirements(setOf(RequiredScope("a"))))
        assertTrue(setOf(GrantedScope("a"), GrantedScope("b")).meetsRequirements(setOf(RequiredScope("a"), RequiredScope("b"))))
        assertTrue(setOf(GrantedScope("a"), GrantedScope("b")).meetsRequirements(setOf(RequiredScope("a:c"))))

        // Mixed narrow and broad grants
        val grants = setOf(GrantedScope("a:b"), GrantedScope("x"))
        assertTrue(grants.meetsRequirements(setOf(RequiredScope("a:b"))))
        assertTrue(grants.meetsRequirements(setOf(RequiredScope("x:y"))))
        assertFalse(grants.meetsRequirements(setOf(RequiredScope("a"))))
    }

    @Test
    fun simplify_complexMixtures() {
        // Incoming broader should remove prior narrower
        assertEquals(
            setOf(RequiredScope("a")),
            listOf(RequiredScope("a:b"), RequiredScope("a")).simplify()
        )
        // Incoming narrower should be skipped if broader exists
        assertEquals(
            setOf(RequiredScope("a")),
            listOf(RequiredScope("a"), RequiredScope("a:b"), RequiredScope("a:c")).simplify()
        )
        // Unrelated branches remain
        assertEquals(
            setOf(RequiredScope("a"), RequiredScope("b:c")),
            listOf(RequiredScope("a"), RequiredScope("b:c")).simplify()
        )
        // Duplicates collapsed
        assertEquals(
            setOf(RequiredScope("a")),
            listOf(RequiredScope("a"), RequiredScope("a"), RequiredScope("a:b")).simplify()
        )
        // Deep nesting
        assertEquals(
            setOf(RequiredScope("a")),
            listOf(RequiredScope("a:b:c:d"), RequiredScope("a:b"), RequiredScope("a")).simplify()
        )
    }

    @Test
    fun startsWithEdgeIntuitionViaPublicAPIs() {
        // These mimic startsWith behavior via public meetsRequirements/contains
        // Sibling mismatch
        assertFalse(GrantedScope("a:b").meetsRequirements(RequiredScope("a:c")))
        // Longer required path than granted -> still allowed only if required starts with granted
        assertTrue(GrantedScope("a").meetsRequirements(RequiredScope("a:b:c")))
        assertFalse(GrantedScope("a:b").meetsRequirements(RequiredScope("a")))
    }
}
