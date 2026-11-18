package com.lightningkite.lightningserver.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProxiedSourceIpTest {
    @Test
    fun takes_the_entry_the_proxy_appended_not_the_clients_claim() {
        assertEquals("203.0.113.9", proxiedSourceIp(listOf("6.6.6.6, 203.0.113.9")))
    }

    @Test
    fun a_semicolon_in_the_clients_claim_does_not_hide_the_proxys_entry() {
        assertEquals("203.0.113.9", proxiedSourceIp(listOf("6.6.6.6;, 203.0.113.9")))
    }

    @Test
    fun uses_the_last_header_line() {
        assertEquals("203.0.113.9", proxiedSourceIp(listOf("6.6.6.6", "7.7.7.7, 203.0.113.9")))
    }

    @Test
    fun single_value_header() {
        assertEquals("203.0.113.9", proxiedSourceIp(listOf("203.0.113.9")))
    }

    @Test
    fun missing_header() {
        assertNull(proxiedSourceIp(emptyList()))
    }
}
