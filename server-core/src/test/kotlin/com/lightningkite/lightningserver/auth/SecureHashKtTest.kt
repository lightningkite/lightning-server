package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.encryption.checkHash
import com.lightningkite.lightningserver.encryption.secureHash
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
            assertTrue("asdf".checkHash(hash))
        }.also { println(it) }
        measureTimeMillis {
            assertFalse("asdff".checkHash(hash))
        }.also { println(it) }
    }
}