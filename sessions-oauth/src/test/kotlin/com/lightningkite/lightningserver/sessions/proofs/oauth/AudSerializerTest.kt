package com.lightningkite.lightningserver.sessions.proofs.oauth

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The OIDC `aud` claim is allowed to be either a single string or an array of strings in the
 * wire format. [AudSerializer] must accept both shapes on read so we don't reject perfectly
 * valid IdP tokens, and produce a stable shape on write so round-trips are clean.
 */
class AudSerializerTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `string aud is read as single-element list`() {
        val raw = """{"iss":"x","sub":"y","aud":"only-one","exp":1,"iat":0}"""
        val claims = json.decodeFromString(OidcIdTokenClaims.serializer(), raw)
        assertEquals(listOf("only-one"), claims.aud)
    }

    @Test
    fun `array aud is preserved`() {
        val raw = """{"iss":"x","sub":"y","aud":["a","b"],"exp":1,"iat":0}"""
        val claims = json.decodeFromString(OidcIdTokenClaims.serializer(), raw)
        assertEquals(listOf("a", "b"), claims.aud)
    }

    @Test
    fun `aud as number is rejected`() {
        val raw = """{"iss":"x","sub":"y","aud":42,"exp":1,"iat":0}"""
        assertFailsWith<Exception> {
            json.decodeFromString(OidcIdTokenClaims.serializer(), raw)
        }
    }

    @Test
    fun `aud as object is rejected`() {
        val raw = """{"iss":"x","sub":"y","aud":{"bad":true},"exp":1,"iat":0}"""
        assertFailsWith<Exception> {
            json.decodeFromString(OidcIdTokenClaims.serializer(), raw)
        }
    }
}
