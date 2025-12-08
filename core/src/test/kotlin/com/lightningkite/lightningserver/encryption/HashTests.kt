package com.lightningkite.lightningserver.encryption

import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.time.measureTime

/**
 * Tests for secure password hashing using PBKDF2-HMAC-SHA512.
 */
class HashTests {
    /**
     * Tests that password hashing is consistent - the same password
     * with the same salt produces the same hash.
     */
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

    /**
     * Performance comparison between Kotlin cryptography library
     * and Java javax.crypto implementations.
     */
    @Test fun hashPerformance(): Unit = runBlocking {
        measureTime { repeat(5) { "asdfa".secureHash() } }.also { println("Kotlin: $it") }
        measureTime { repeat(5) { "asdfa".secureHashJava() } }.also { println("Java: $it") }
        val hash = "asdfa".secureHash()
        measureTime { repeat(5) { "asdfa".checkAgainstHash(hash) } }.also { println("Kotlin: $it") }
        measureTime { repeat(5) { "asdfa".checkAgainstHashJava(hash) } }.also { println("Java: $it") }
    }

    /**
     * Tests that each hash uses a unique random salt, so the same
     * password produces different hashes on each call.
     */
    @Test
    fun hashingIsSalted(): Unit = runBlocking {
        val data = "Hello World"
        val h1 = data.secureHash()
        val h2 = data.secureHash()
        assertNotEquals(h1, h2)
    }

    /**
     * Tests cross-compatibility between Kotlin and Java implementations -
     * both should be able to verify hashes created by the other.
     */
    @Test
    fun hashingIsConsistentWithOld(): Unit = runBlocking {
        val data = "Hello World"
        val h1 = data.secureHash()
        val h2 = data.secureHashJava()

        // Internal consistency first
        assert(data.checkAgainstHash(h1))
        assert(data.checkAgainstHashJava(h2))

        // External consistency second
        assert(data.checkAgainstHashJava(h1))
        assert(data.checkAgainstHash(h2))
    }
}