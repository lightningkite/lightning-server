package com.lightningkite.lightningserver.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScopeTests {

    @Test
    fun singleScopeAcceptance() {
        assertEquals(true, GrantedScope("auth").meetsRequirements(RequiredScope("auth")))
        assertEquals(true, GrantedScope("auth").meetsRequirements(RequiredScope("auth:sub")))
        assertEquals(true, GrantedScope("auth:sub").meetsRequirements(RequiredScope("auth:sub")))
        assertEquals(false, GrantedScope("auth:sub").meetsRequirements(RequiredScope("auth")))
        assertEquals(true, GrantedScope("auth").meetsRequirements(RequiredScope("auth:sub1:sub2:sub3")))
    }

    @Test
    fun multiScopeAcceptance() {
        assertEquals(true, setOf("auth", "scope").mapTo(HashSet(), ::GrantedScope).meetsRequirements(setOf("auth", "scope").mapTo(HashSet(), ::RequiredScope)))
        assertEquals(true, setOf("auth", "scope").mapTo(HashSet(), ::GrantedScope).meetsRequirements(setOf("auth:sub", "scope:sub").mapTo(HashSet(), ::RequiredScope)))
        assertEquals(true, setOf("auth:sub", "scope").mapTo(HashSet(), ::GrantedScope).meetsRequirements(setOf("auth:sub", "scope:sub").mapTo(HashSet(), ::RequiredScope)))
        assertEquals(true, setOf("*").mapTo(HashSet(), ::GrantedScope).meetsRequirements(setOf("auth", "scope", "*").mapTo(HashSet(), ::RequiredScope)))
        assertEquals(false, setOf("auth:sub", "scope").mapTo(HashSet(), ::GrantedScope).meetsRequirements(setOf("*").mapTo(HashSet(), ::RequiredScope)))
    }


    @Test fun contains() {
        assertTrue(RequiredScope("a") in RequiredScope("a"))
        assertTrue(RequiredScope("a") in RequiredScope("*"))
        assertTrue(RequiredScope("a:b") in RequiredScope("a"))
        assertTrue(RequiredScope("a:b") in RequiredScope("*"))
        assertFalse(RequiredScope("a") in RequiredScope("a:b"))
    }


    @Test fun simplify() {
        assertEquals(
            setOf("*").mapTo(LinkedHashSet(), ::RequiredScope),
            setOf("*").mapTo(LinkedHashSet(), ::RequiredScope).simplify()
        )
        assertEquals(
            setOf("a").mapTo(LinkedHashSet(), ::RequiredScope),
            setOf("a").mapTo(LinkedHashSet(), ::RequiredScope).simplify()
        )
        assertEquals(
            setOf("a").mapTo(LinkedHashSet(), ::RequiredScope),
            setOf("a:b", "a").mapTo(LinkedHashSet(), ::RequiredScope).simplify()
        )
        assertEquals(
            setOf("a").mapTo(LinkedHashSet(), ::RequiredScope),
            setOf("a", "a:b").mapTo(LinkedHashSet(), ::RequiredScope).simplify()
        )
        assertEquals(
            setOf("a", "b").mapTo(LinkedHashSet(), ::RequiredScope),
            setOf("a", "b").mapTo(LinkedHashSet(), ::RequiredScope).simplify()
        )
        assertEquals(
            setOf("a", "b:c").mapTo(LinkedHashSet(), ::RequiredScope),
            setOf("a", "b:c").mapTo(LinkedHashSet(), ::RequiredScope).simplify()
        )
        assertEquals(
            setOf("a", "b").mapTo(LinkedHashSet(), ::RequiredScope),
            setOf("a", "b:c", "b").mapTo(LinkedHashSet(), ::RequiredScope).simplify()
        )
    }
}