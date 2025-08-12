package com.lightningkite.lightningserver.runtime.cors

import com.lightningkite.lightningserver.runtime.originMatches
import junit.framework.TestCase.assertFalse
import org.junit.Test
import kotlin.test.assertTrue

class TestCorsOriginMatchs {

    @Test
    fun testOriginMatchesWildCard(){
        var allowed = listOf("*")

        assertTrue(originMatches(allowed, "some.domain"))
        assertTrue(originMatches(allowed, "https://some.domain"))
        assertTrue(originMatches(allowed, "http://some.domain"))
        assertTrue(originMatches(allowed, "some.long.absurd.domain.for.no.reason"))
        assertTrue(originMatches(allowed, "*"))
        assertTrue(originMatches(allowed, ""))

        allowed = listOf("https://*")
        assertFalse(originMatches(allowed, "some.domain"))
        assertTrue(originMatches(allowed, "https://some.domain"))
        assertFalse(originMatches(allowed, "http://some.domain"))
        assertFalse(originMatches(allowed, "some.long.absurd.domain.for.no.reason"))
        assertFalse(originMatches(allowed, "*"))
        assertFalse(originMatches(allowed, ""))

    }

    @Test
    fun testOriginMatchesExactMatch(){
        var allowed = listOf("some.domain")

        assertTrue(originMatches(allowed, "some.domain"))
        assertFalse(originMatches(allowed, "sub.some.domain"))
        assertFalse(originMatches(allowed, "other.domain"))
        assertFalse(originMatches(allowed, "some.long.absurd.domain.for.no.reason"))
        assertFalse(originMatches(allowed, "*"))
        assertFalse(originMatches(allowed, ""))

        allowed = listOf("some.domain", "sub.some.domain", "other.domain")

        assertTrue(originMatches(allowed, "some.domain"))
        assertTrue(originMatches(allowed, "sub.some.domain"))
        assertTrue(originMatches(allowed, "other.domain"))
        assertFalse(originMatches(allowed, "some.long.absurd.domain.for.no.reason"))
    }

    @Test
    fun testOriginMatchesSubWildCard(){
        var allowed = listOf("*.some.domain")

        assertFalse(originMatches(allowed, "some.domain"))
        assertFalse(originMatches(allowed, "other.domain"))
        assertFalse(originMatches(allowed, "some.long.absurd.domain.for.no.reason"))
        assertFalse(originMatches(allowed, "*"))
        assertFalse(originMatches(allowed, ""))
        assertTrue(originMatches(allowed, "sub.some.domain"))
        assertTrue(originMatches(allowed, "a.really.long.sub.domain.for.some.reason.some.domain"))
    }

    @Test
    fun testOriginMatchesSchema(){
        var allowed = listOf("some.domain")

        assertTrue(originMatches(allowed, "some.domain"))
        assertTrue(originMatches(allowed, "http://some.domain"))
        assertTrue(originMatches(allowed, "https://some.domain"))
        assertTrue(originMatches(allowed, "wss://some.domain"))
        assertTrue(originMatches(allowed, "ws://some.domain"))

        allowed = listOf("http://some.domain")
        assertFalse(originMatches(allowed, "some.domain"))
        assertTrue(originMatches(allowed, "http://some.domain"))
        assertFalse(originMatches(allowed, "https://some.domain"))
        assertFalse(originMatches(allowed, "wss://some.domain"))
        assertFalse(originMatches(allowed, "ws://some.domain"))

        allowed = listOf("http://*.some.domain")
        assertFalse(originMatches(allowed, "some.domain"))
        assertFalse(originMatches(allowed, "http://some.domain"))
        assertFalse(originMatches(allowed, "https://some.domain"))
        assertFalse(originMatches(allowed, "wss://some.domain"))
        assertFalse(originMatches(allowed, "ws://some.domain"))
        assertFalse(originMatches(allowed, "sub.some.domain"))
        assertTrue(originMatches(allowed, "http://sub.some.domain"))
        assertFalse(originMatches(allowed, "https://sub.some.domain"))
        assertFalse(originMatches(allowed, "wss://sub.some.domain"))
        assertFalse(originMatches(allowed, "ws://sub.some.domain"))

    }
}