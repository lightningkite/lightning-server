package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.encryption.checkAgainstHash
import com.lightningkite.lightningserver.encryption.secureHash
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class SecureHashKtTest {
    @Test
    fun realTest(): Unit = runBlocking {
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