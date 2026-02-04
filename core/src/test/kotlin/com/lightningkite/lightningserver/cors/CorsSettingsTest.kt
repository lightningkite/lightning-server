// Tests added by Claude
package com.lightningkite.lightningserver.cors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [CorsSettings] data class and its companion object factory methods.
 *
 * Tests cover:
 * - Default constructor values
 * - `allowAll()` factory method configuration
 * - `forProduction()` factory method configuration
 * - Property correctness and serialization concerns
 */
class CorsSettingsTest {

    // Default constructor tests

    @Test
    fun `default constructor creates restrictive settings`() {
        val settings = CorsSettings()
        assertTrue(settings.limitToDomains.isEmpty())
        assertTrue(settings.limitToHeaders.isEmpty())
        assertTrue(settings.limitToMethods.isEmpty())
        assertTrue(settings.exposedHeaders.isEmpty())
        assertFalse(settings.allowCredentials)
        assertEquals(null, settings.cacheLength)
        assertTrue(settings.forbidOnMatchFail)
    }

    @Test
    fun `default limitToDomains is empty list meaning no origins allowed`() {
        val settings = CorsSettings()
        // Empty list = no origins allowed (restrictive default)
        assertTrue(settings.limitToDomains.isEmpty())
    }

    @Test
    fun `custom constructor preserves all values`() {
        val settings = CorsSettings(
            limitToDomains = listOf("https://example.com", "https://*.trusted.com"),
            limitToHeaders = listOf("Content-Type", "Authorization"),
            limitToMethods = listOf("GET", "POST"),
            exposedHeaders = listOf("X-Custom-Header"),
            allowCredentials = true,
            cacheLength = 300.seconds,
            forbidOnMatchFail = false
        )
        assertEquals(listOf("https://example.com", "https://*.trusted.com"), settings.limitToDomains)
        assertEquals(listOf("Content-Type", "Authorization"), settings.limitToHeaders)
        assertEquals(listOf("GET", "POST"), settings.limitToMethods)
        assertEquals(listOf("X-Custom-Header"), settings.exposedHeaders)
        assertTrue(settings.allowCredentials)
        assertEquals<Duration?>(300.seconds, settings.cacheLength)
        assertFalse(settings.forbidOnMatchFail)
    }

    // allowAll() factory method tests

    @Test
    fun `allowAll creates permissive settings for development`() {
        val settings = CorsSettings.allowAll()
        assertEquals(listOf("*"), settings.limitToDomains)
        assertEquals(listOf("*"), settings.limitToHeaders)
        assertEquals(listOf("*"), settings.limitToMethods)
        assertTrue(settings.allowCredentials)
        assertEquals<Duration?>(10.seconds, settings.cacheLength)
        assertFalse(settings.forbidOnMatchFail)
    }

    @Test
    fun `allowAll has wildcard for domains`() {
        val settings = CorsSettings.allowAll()
        assertEquals(listOf("*"), settings.limitToDomains)
    }

    @Test
    fun `allowAll has wildcard for headers`() {
        val settings = CorsSettings.allowAll()
        assertEquals(listOf("*"), settings.limitToHeaders)
    }

    @Test
    fun `allowAll has wildcard for methods`() {
        val settings = CorsSettings.allowAll()
        assertEquals(listOf("*"), settings.limitToMethods)
    }

    @Test
    fun `allowAll enables credentials`() {
        // Note: This violates CORS spec when used with wildcard origins
        // browsers will reject credentials with wildcard origins
        val settings = CorsSettings.allowAll()
        assertTrue(settings.allowCredentials)
    }

    @Test
    fun `allowAll disables forbidOnMatchFail`() {
        val settings = CorsSettings.allowAll()
        assertFalse(settings.forbidOnMatchFail)
    }

    @Test
    fun `allowAll has 10 second cache`() {
        val settings = CorsSettings.allowAll()
        assertEquals<Duration?>(10.seconds, settings.cacheLength)
    }

    @Test
    fun `allowAll has empty exposedHeaders`() {
        val settings = CorsSettings.allowAll()
        assertTrue(settings.exposedHeaders.isEmpty())
    }

    // forProduction() factory method tests

    @Test
    fun `forProduction creates settings with specified origins`() {
        val settings = CorsSettings.forProduction("https://example.com")
        assertEquals(listOf("https://example.com"), settings.limitToDomains)
    }

    @Test
    fun `forProduction with multiple origins`() {
        val settings = CorsSettings.forProduction(
            "https://example.com",
            "https://app.example.com",
            "https://*.trusted.com"
        )
        assertEquals(
            listOf("https://example.com", "https://app.example.com", "https://*.trusted.com"),
            settings.limitToDomains
        )
    }

    @Test
    fun `forProduction with no origins creates empty list`() {
        val settings = CorsSettings.forProduction()
        assertTrue(settings.limitToDomains.isEmpty())
    }

    @Test
    fun `forProduction uses wildcard headers for convenience`() {
        val settings = CorsSettings.forProduction("https://example.com")
        assertEquals(listOf("*"), settings.limitToHeaders)
    }

    @Test
    fun `forProduction uses wildcard methods for convenience`() {
        val settings = CorsSettings.forProduction("https://example.com")
        assertEquals(listOf("*"), settings.limitToMethods)
    }

    @Test
    fun `forProduction enables credentials`() {
        val settings = CorsSettings.forProduction("https://example.com")
        assertTrue(settings.allowCredentials)
    }

    @Test
    fun `forProduction has 10 second cache`() {
        val settings = CorsSettings.forProduction("https://example.com")
        assertEquals<Duration?>(10.seconds, settings.cacheLength)
    }

    @Test
    fun `forProduction enables forbidOnMatchFail by default`() {
        // Uses the default which is true
        val settings = CorsSettings.forProduction("https://example.com")
        assertTrue(settings.forbidOnMatchFail)
    }

    @Test
    fun `forProduction has empty exposedHeaders`() {
        val settings = CorsSettings.forProduction("https://example.com")
        assertTrue(settings.exposedHeaders.isEmpty())
    }

    // Data class copy tests

    @Test
    fun `copy preserves all fields when none specified`() {
        val original = CorsSettings(
            limitToDomains = listOf("https://example.com"),
            limitToHeaders = listOf("Content-Type"),
            limitToMethods = listOf("GET"),
            exposedHeaders = listOf("X-Custom"),
            allowCredentials = true,
            cacheLength = 100.seconds,
            forbidOnMatchFail = false
        )
        val copied = original.copy()
        assertEquals(original, copied)
    }

    @Test
    fun `copy allows overriding individual fields`() {
        val original = CorsSettings.forProduction("https://example.com")
        val modified = original.copy(
            allowCredentials = false,
            cacheLength = 3600.seconds
        )
        assertEquals(listOf("https://example.com"), modified.limitToDomains)
        assertFalse(modified.allowCredentials)
        assertEquals<Duration?>(3600.seconds, modified.cacheLength)
    }

    // Edge case tests

    @Test
    fun `cacheLength can be null`() {
        val settings = CorsSettings(cacheLength = null)
        assertEquals(null, settings.cacheLength)
    }

    @Test
    fun `cacheLength can be zero`() {
        val settings = CorsSettings(cacheLength = 0.seconds)
        assertEquals<Duration?>(0.seconds, settings.cacheLength)
    }
}
