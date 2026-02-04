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

    // Tests added by Claude for edge cases
    @Test
    fun `case insensitive scheme matching`() {
        val allowed = listOf("HTTPS://example.com")
        assertTrue(originMatches(allowed, "https://example.com"))
        assertTrue(originMatches(allowed, "HTTPS://example.com"))
        assertFalse(originMatches(allowed, "http://example.com"))
    }

    @Test
    fun `case insensitive domain matching`() {
        val allowed = listOf("https://EXAMPLE.COM")
        assertTrue(originMatches(allowed, "https://example.com"))
        assertTrue(originMatches(allowed, "https://EXAMPLE.COM"))
        assertTrue(originMatches(allowed, "https://Example.Com"))
    }

    @Test
    fun `wildcard subdomain with case insensitive matching`() {
        val allowed = listOf("https://*.EXAMPLE.COM")
        assertTrue(originMatches(allowed, "https://sub.example.com"))
        assertTrue(originMatches(allowed, "https://SUB.EXAMPLE.COM"))
    }

    @Test
    fun `origin without scheme when allowed has scheme - edge case by Claude`() {
        // This tests the edge case behavior when origin is passed without ://
        // In practice, browsers always send origins with schemes, but this documents
        // the current behavior if such an origin were encountered
        val allowed = listOf("https://example.com")
        // When origin has no "://", substringBefore returns the whole string as "scheme"
        // and substringAfter returns the whole string as "domain"
        // So "example.com" origin would have scheme="example.com" and domain="example.com"
        // This would NOT match "https://example.com" because schemes differ
        assertFalse(originMatches(allowed, "example.com"))
    }

    @Test
    fun `origin without scheme when allowed has no scheme - edge case by Claude`() {
        // When both allowed and origin have no scheme, they can still match
        // Allowed: scheme="", domain="example.com"
        // Origin: scheme="example.com", domain="example.com"
        // Since allowed scheme is blank, it allows any scheme
        val allowed = listOf("example.com")
        assertTrue(originMatches(allowed, "example.com"))
    }

    @Test
    fun `wildcard star matches empty origin - edge case by Claude`() {
        // Edge case: what if origin is empty string?
        val allowed = listOf("*")
        assertTrue(originMatches(allowed, ""))
    }

    @Test
    fun `specific wildcard does not match malformed origins - edge case by Claude`() {
        val allowed = listOf("https://*.example.com")
        // Empty origin with scheme would have empty domain
        assertFalse(originMatches(allowed, "https://"))
    }
}
