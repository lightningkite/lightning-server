package com.lightningkite.lightningserver.auth

import org.junit.Assert.*
import org.junit.Test

class SecureHashKtTest {
    @Test fun realTest() {
        val hash = "asdf".secureHash()
        println("Hash is $hash")
        assertTrue("asdf".checkHash(hash))
        assertFalse("asdff".checkHash(hash))
    }
}