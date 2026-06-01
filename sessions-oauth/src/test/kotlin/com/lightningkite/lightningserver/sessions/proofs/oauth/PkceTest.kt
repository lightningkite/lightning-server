package com.lightningkite.lightningserver.sessions.proofs.oauth

import org.junit.Test
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PkceTest {

    @OptIn(ExperimentalEncodingApi::class)
    private val urlSafe = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

    @Test
    fun `challenge is sha256 of verifier (S256)`() {
        val pair = Pkce.generate()
        val expected = urlSafe.encode(MessageDigest.getInstance("SHA-256").digest(pair.verifier.encodeToByteArray()))
        assertEquals(expected, pair.challenge)
    }

    @Test
    fun `verifier length is within RFC 7636 bounds`() {
        repeat(5) {
            val pair = Pkce.generate()
            assertTrue(pair.verifier.length in 43..128, "verifier length ${pair.verifier.length} out of range")
        }
    }

    @Test
    fun `successive calls produce different pairs`() {
        val a = Pkce.generate()
        val b = Pkce.generate()
        assertNotEquals(a.verifier, b.verifier)
        assertNotEquals(a.challenge, b.challenge)
    }
}
