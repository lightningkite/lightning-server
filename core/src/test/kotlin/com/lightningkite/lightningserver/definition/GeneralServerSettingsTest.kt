package com.lightningkite.lightningserver.definition

import kotlin.test.Test
import kotlin.test.assertEquals

class GeneralServerSettingsTest {

    @Test
    fun `test default wsUrl for https`() {
        val settings = GeneralServerSettings(publicUrl = "https://example.com")
        assertEquals("wss://example.com", settings.wsUrl)
    }

    @Test
    fun `test default wsUrl for http`() {
        val settings = GeneralServerSettings(publicUrl = "http://localhost:8080")
        assertEquals("ws://localhost:8080", settings.wsUrl)
    }

    @Test
    fun `test publicUrlDomain extraction`() {
        val settings = GeneralServerSettings(publicUrl = "https://api.example.com:8080/path")
        assertEquals("api.example.com:8080", settings.publicUrlDomain)
    }

    @Test
    fun `test publicUrlDomain without path`() {
        val settings = GeneralServerSettings(publicUrl = "https://api.example.com")
        assertEquals("api.example.com", settings.publicUrlDomain)
    }

    @Test
    fun `test wsUrlDomain extraction`() {
        val settings = GeneralServerSettings(wsUrl = "wss://ws.example.com:9000/socket")
        assertEquals("ws.example.com:9000", settings.wsUrlDomain)
    }

    @Test
    fun `test absolutePathAdjustment with no subpath`() {
        val settings = GeneralServerSettings(publicUrl = "https://example.com")
        assertEquals("/api/users", settings.absolutePathAdjustment("/api/users"))
        assertEquals("relative/path", settings.absolutePathAdjustment("relative/path"))
    }

    @Test
    fun `test absolutePathAdjustment with subpath`() {
        val settings = GeneralServerSettings(publicUrl = "https://example.com/v1")
        assertEquals("/v1/api/users", settings.absolutePathAdjustment("/api/users"))
        assertEquals("relative/path", settings.absolutePathAdjustment("relative/path"))
    }

    @Test
    fun `test absolutePathAdjustment with nested subpath`() {
        val settings = GeneralServerSettings(publicUrl = "https://example.com/app/api")
        assertEquals("/app/api/users", settings.absolutePathAdjustment("/users"))
    }

    @Test
    fun `test defaults`() {
        val settings = GeneralServerSettings()
        assertEquals("My Project", settings.projectName)
        assertEquals("http://localhost:8080", settings.publicUrl)
        assertEquals("ws://localhost:8080", settings.wsUrl)
        assertEquals(false, settings.debug)
        assertEquals(null, settings.emergencyContact)
    }
}
