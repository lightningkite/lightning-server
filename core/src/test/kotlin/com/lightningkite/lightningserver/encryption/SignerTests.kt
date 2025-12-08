package com.lightningkite.lightningserver.encryption

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.random.Random

/**
 * Tests for signature generation and verification using HMAC-based signers.
 */
class SignerTests {
    val basis = SecretBasis()

    private suspend fun Signer.test(iterations: Int = 100) {
        repeat(iterations) {
            val data = Random.nextBytes(32)
            val signed = sign(data)
            assert(verify(data, signed))
        }
    }

    private val signers = listOf(
        basis::HS256,
        basis::HS384,
        basis::HS512,
    )

    /**
     * Tests JWT-compatible signers (HS256, HS384, HS512) for correct
     * signing and verification across multiple variants.
     */
    @Test
    fun jwtSigners(): Unit = runBlocking {
        signers.forEach { getSigner ->
            repeat(100) {
                val signer = getSigner(it.toString())

                println("Testing ${signer.name}($it)")
                signer.test()
            }
        }
    }

    /**
     * Tests that different signer variants produce cryptographically
     * independent keys that cannot verify each other's signatures.
     */
    @Test
    fun variantsAreUnique(): Unit = runBlocking {
        signers.forEach { getSigner ->
            repeat(100) {
                val s1 = getSigner("signer1:$it")
                val s2 = getSigner("signer2:$it")

                s1.test(50)
                s2.test(50)

                repeat(10) {
                    val data = Random.nextBytes(Random.nextInt(64))

                    val first = s1.sign(data)
                    assert(!s2.verify(data, first)) { "s2 verified s1" }

                    val second = s2.sign(data)
                    assert(!s1.verify(data, second)) { "s1 verified s2" }
                }
            }
        }
    }
}