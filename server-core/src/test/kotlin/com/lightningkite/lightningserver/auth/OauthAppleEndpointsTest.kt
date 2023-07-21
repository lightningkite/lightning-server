package com.lightningkite.lightningserver.auth

import com.lightningkite.lightningserver.bytes.toHexString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.assertContentEquals

class OauthAppleEndpointsTest {
    @Test
    fun testAppleLengthFail() {
        val s = OauthProviderCredentialsApple(
            serviceId = "serviceId",
            teamId = "teamId",
            keyId = "keyId",
            keyString = "MIGTAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBHkwdwIBAQQgIWUMQ84xzhP2e+kbMr8mTO4gsESWuG3m3iB7MH3Xo5+gCgYIKoZIzj0DAQehRANCAAR9iyvE7qiRcPtnveO4Pq9Bj05jB9/66qoUbw8drCk+zPZny7oGZ8+NHPIkBEMfxBk3B4fvC2UNwt7yfgN56h2f"
        )
        repeat(20) {
            s.generateJwt().let {
                println("${it.length} - $it")
            }
        }
    }

    @Test
    fun signCheck() {
        BigInteger("115683617312640515022426693655693887561717352407539298898090610843846182953363").let {
            assertEquals(it, fromSigned32(it.signed32().also { println(it.toHexString()) }))
            println("Works!")
        }
        repeat(1000) {
            Random.nextBytes(32).let {
                assertContentEquals(it, fromSigned32(it).signed32())
            }
        }
    }

    @Test
    fun signCheck2() {
        val signer =
            SecureHasher.ECDSA256("MIGTAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBHkwdwIBAQQgIWUMQ84xzhP2e+kbMr8mTO4gsESWuG3m3iB7MH3Xo5+gCgYIKoZIzj0DAQehRANCAAR9iyvE7qiRcPtnveO4Pq9Bj05jB9/66qoUbw8drCk+zPZny7oGZ8+NHPIkBEMfxBk3B4fvC2UNwt7yfgN56h2f")
        repeat(1000) {
            val o = Random.nextBytes(64)
            println("-- $it --")
            println("Original bytes: ${o.toHexString()}")
            assertTrue(signer.verify(o, signer.sign(o).also { println("Signature: ${it.toHexString()}") }))
        }
    }
}