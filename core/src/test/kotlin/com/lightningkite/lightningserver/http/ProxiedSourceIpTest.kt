package com.lightningkite.lightningserver.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProxiedSourceIpTest {
    @Test
    fun takes_the_entry_the_proxy_appended_not_the_clients_claim() {
        assertEquals("203.0.113.9", HttpHeaders("X-Forwarded-For" to "6.6.6.6, 203.0.113.9").getProxiedSourceIp("X-Forwarded-For"))
    }

    @Test
    fun a_semicolon_in_the_clients_claim_does_not_hide_the_proxys_entry() {
        assertEquals("203.0.113.9", HttpHeaders("X-Forwarded-For" to "6.6.6.6;, 203.0.113.9").getProxiedSourceIp("X-Forwarded-For"))
    }

    @Test
    fun uses_the_last_header_line() {
        val headers = HttpHeaders("X-Forwarded-For" to "6.6.6.6", "X-Forwarded-For" to "7.7.7.7, 203.0.113.9")
        assertEquals("203.0.113.9", headers.getProxiedSourceIp("X-Forwarded-For"))
    }

    @Test
    fun header_name_is_case_insensitive() {
        assertEquals("203.0.113.9", HttpHeaders("x-real-ip" to "203.0.113.9").getProxiedSourceIp("X-Real-IP"))
    }

    @Test
    fun missing_header() {
        assertNull(HttpHeaders.EMPTY.getProxiedSourceIp("X-Forwarded-For"))
    }
}
