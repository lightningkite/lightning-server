package com.lightningkite.lightningserver.encryption

import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotEquals

class HashTests {
    @Test
    fun hashingIsConsistent(): Unit = runBlocking {
        repeat(10) {
            println("Test #${it + 1}")
            val data = Base64.encode(Random.nextBytes(Random.nextInt(8, 64)))
            val secret = data.secureHash()
            assert(data.checkAgainstHash(secret)) {
                """
                    Data: $data
                    Hashed: $secret
                """.trimIndent()
            }
        }
    }

    @Test
    fun hashingIsSalted(): Unit = runBlocking {
        val data = "Hello World"
        val h1 = data.secureHash()
        val h2 = data.secureHash()
        assertNotEquals(h1, h2)
    }
}