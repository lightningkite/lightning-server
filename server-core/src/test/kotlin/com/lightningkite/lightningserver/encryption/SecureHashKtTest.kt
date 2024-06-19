package com.lightningkite.lightningserver.encryption

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class SecureHashKtTest {
    @Test
    fun realTest() {
        val hash = "asdf".secureHash()
        println("Hash is $hash")
        measureTimeMillis {
            assertTrue("asdf".checkAgainstHash(hash))
        }.also { println(it) }
        measureTimeMillis {
            assertFalse("asdff".checkAgainstHash(hash))
        }.also { println(it) }
    }
}