package com.lightningkite.lightningserver.auth

import org.junit.Assert.*
import org.junit.Test
import kotlin.system.measureTimeMillis
import kotlin.time.measureTime

class SecureHashKtTest {
    @Test fun realTest() {
        val hash = "asdf".secureHash()
        println("Hash is $hash")
        measureTimeMillis {
            assertTrue("asdf".checkHash(hash))
        }.also { println(it) }
        measureTimeMillis {
            assertFalse("asdff".checkHash(hash))
        }.also { println(it) }
    }
}