package com.lightningkite.lightningserver.encryption

import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.time.measureTime

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

    @Test fun hashPerformance(): Unit = runBlocking {
        measureTime { repeat(5) { "asdfa".secureHash() } }.also { println("Kotlin: $it") }
        measureTime { repeat(5) { "asdfa".secureHashJava() } }.also { println("Java: $it") }
        val hash = "asdfa".secureHash()
        measureTime { repeat(5) { "asdfa".checkAgainstHash(hash) } }.also { println("Kotlin: $it") }
        measureTime { repeat(5) { "asdfa".checkAgainstHashJava(hash) } }.also { println("Java: $it") }
    }

    @Test
    fun hashingIsSalted(): Unit = runBlocking {
        val data = "Hello World"
        val h1 = data.secureHash()
        val h2 = data.secureHash()
        assertNotEquals(h1, h2)
    }

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