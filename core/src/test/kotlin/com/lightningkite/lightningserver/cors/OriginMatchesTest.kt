package com.lightningkite.lightningserver.cors

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the originMatches function.
 *
 * Tests wildcard matching, scheme matching, and exact domain matching.
 */
class OriginMatchesTest {

    @Test
    fun `exact match with scheme`() {
        val allowed = listOf("https://example.com")
        assertTrue(originMatches(allowed, "https://example.com"))
        assertFalse(originMatches(allowed, "http://example.com"))
        assertFalse(originMatches(allowed, "https://other.com"))
    }

    @Test
    fun `exact match without scheme allows any scheme`() {
        val allowed = listOf("example.com")
        assertTrue(originMatches(allowed, "https://example.com"))
        assertTrue(originMatches(allowed, "http://example.com"))
        assertTrue(originMatches(allowed, "wss://example.com"))
        assertFalse(originMatches(allowed, "https://other.com"))
    }

    @Test
    fun `wildcard matches all origins`() {
        val allowed = listOf("*")
        assertTrue(originMatches(allowed, "https://example.com"))
        assertTrue(originMatches(allowed, "http://any.domain.com"))
        assertTrue(originMatches(allowed, "https://subdomain.example.com"))
    }

    @Test
    fun `subdomain wildcard with scheme`() {
        val allowed = listOf("https://*.example.com")
        assertTrue(originMatches(allowed, "https://sub.example.com"))
        assertTrue(originMatches(allowed, "https://deep.sub.example.com"))
        assertFalse(originMatches(allowed, "http://sub.example.com")) // Wrong scheme
        assertFalse(originMatches(allowed, "https://example.com")) // No subdomain
        assertFalse(originMatches(allowed, "https://other.com"))
    }

    @Test
    fun `subdomain wildcard without scheme allows any scheme`() {
        val allowed = listOf("*.example.com")
        assertTrue(originMatches(allowed, "https://sub.example.com"))
        assertTrue(originMatches(allowed, "http://sub.example.com"))
        assertTrue(originMatches(allowed, "wss://sub.example.com"))
        assertTrue(originMatches(allowed, "https://deep.sub.example.com"))
        assertFalse(originMatches(allowed, "https://example.com")) // No subdomain
    }

    @Test
    fun `multiple allowed origins - matches first`() {
        val allowed = listOf("https://example.com", "https://other.com")
        assertTrue(originMatches(allowed, "https://example.com"))
        assertTrue(originMatches(allowed, "https://other.com"))
        assertFalse(originMatches(allowed, "https://third.com"))
    }

    @Test
    fun `multiple allowed origins - matches second`() {
        val allowed = listOf("https://example.com", "https://*.other.com")
        assertTrue(originMatches(allowed, "https://example.com"))
        assertTrue(originMatches(allowed, "https://sub.other.com"))
        assertFalse(originMatches(allowed, "https://third.com"))
    }

    @Test
    fun `empty list matches nothing`() {
        val allowed = emptyList<String>()
        assertFalse(originMatches(allowed, "https://example.com"))
        assertFalse(originMatches(allowed, "http://any.com"))
    }

    @Test
    fun `localhost variants`() {
        val allowed = listOf("http://localhost:3000", "http://localhost:8080")
        assertTrue(originMatches(allowed, "http://localhost:3000"))
        assertTrue(originMatches(allowed, "http://localhost:8080"))
        assertFalse(originMatches(allowed, "http://localhost:9000"))
        assertFalse(originMatches(allowed, "https://localhost:3000")) // Wrong scheme
    }

    @Test
    fun `port numbers are part of domain matching`() {
        val allowed = listOf("https://example.com:8080")
        assertTrue(originMatches(allowed, "https://example.com:8080"))
        assertFalse(originMatches(allowed, "https://example.com")) // No port
        assertFalse(originMatches(allowed, "https://example.com:9000")) // Different port
    }

    @Test
    fun `wildcard with port`() {
        val allowed = listOf("*.example.com:8080")
        assertTrue(originMatches(allowed, "https://sub.example.com:8080"))
        assertTrue(originMatches(allowed, "http://sub.example.com:8080"))
        assertFalse(originMatches(allowed, "https://sub.example.com")) // No port
        assertFalse(originMatches(allowed, "https://sub.example.com:9000")) // Different port
    }
}
