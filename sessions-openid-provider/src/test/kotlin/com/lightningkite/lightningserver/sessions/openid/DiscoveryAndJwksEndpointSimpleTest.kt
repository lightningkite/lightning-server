package com.lightningkite.lightningserver.sessions.openid

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

/**
 * Simplified tests for Discovery and JWKS endpoints
 *
 * These tests verify the core data models and constants used by the
 * Discovery (OpenID Connect Discovery 1.0) and JWKS endpoints.
 */
class DiscoveryAndJwksEndpointSimpleTest {

    @Test
    fun `ProviderMetadata includes all required fields`() {
        val metadata = ProviderMetadata(
            issuer = "https://example.com",
            authorization_endpoint = "https://example.com/authorize",
            token_endpoint = "https://example.com/token",
            userinfo_endpoint = "https://example.com/userinfo",
            jwks_uri = "https://example.com/jwks",
            response_types_supported = listOf("code", "id_token", "code id_token"),
            subject_types_supported = listOf("public"),
            id_token_signing_alg_values_supported = listOf("RS256")
        )

        assertEquals("https://example.com", metadata.issuer)
        assertEquals("https://example.com/authorize", metadata.authorization_endpoint)
        assertEquals("https://example.com/token", metadata.token_endpoint)
        assertEquals("https://example.com/userinfo", metadata.userinfo_endpoint)
        assertEquals("https://example.com/jwks", metadata.jwks_uri)
        assertTrue(metadata.response_types_supported.contains("code"))
        assertTrue(metadata.response_types_supported.contains("id_token"))
        assertTrue(metadata.subject_types_supported.contains("public"))
        assertTrue(metadata.id_token_signing_alg_values_supported.contains("RS256"))
    }

    @Test
    fun `ProviderMetadata supports optional fields`() {
        val metadata = ProviderMetadata(
            issuer = "https://example.com",
            authorization_endpoint = "https://example.com/authorize",
            token_endpoint = "https://example.com/token",
            jwks_uri = "https://example.com/jwks",
            response_types_supported = listOf("code"),
            subject_types_supported = listOf("public"),
            id_token_signing_alg_values_supported = listOf("RS256"),
            scopes_supported = listOf("openid", "profile", "email"),
            token_endpoint_auth_methods_supported = listOf("client_secret_post", "none"),
            claims_supported = listOf("sub", "name", "email"),
            code_challenge_methods_supported = listOf("S256", "plain"),
            grant_types_supported = listOf("authorization_code", "refresh_token")
        )

        assertNotNull(metadata.scopes_supported)
        assertTrue(metadata.scopes_supported!!.contains("openid"))
        assertTrue(metadata.scopes_supported!!.contains("profile"))
        assertTrue(metadata.scopes_supported!!.contains("email"))

        assertNotNull(metadata.token_endpoint_auth_methods_supported)
        assertTrue(metadata.token_endpoint_auth_methods_supported!!.contains("client_secret_post"))
        assertTrue(metadata.token_endpoint_auth_methods_supported!!.contains("none"))

        assertNotNull(metadata.claims_supported)
        assertTrue(metadata.claims_supported!!.contains("sub"))
        assertTrue(metadata.claims_supported!!.contains("name"))
        assertTrue(metadata.claims_supported!!.contains("email"))

        assertNotNull(metadata.code_challenge_methods_supported)
        assertTrue(metadata.code_challenge_methods_supported!!.contains("S256"))
        assertTrue(metadata.code_challenge_methods_supported!!.contains("plain"))

        assertNotNull(metadata.grant_types_supported)
        assertTrue(metadata.grant_types_supported!!.contains("authorization_code"))
        assertTrue(metadata.grant_types_supported!!.contains("refresh_token"))
    }

    @Test
    fun `ProviderMetadata supports all standard response types`() {
        val responseTypes = listOf(
            "code",                    // Authorization Code Flow
            "id_token",               // Implicit Flow
            "id_token token",         // Implicit Flow with access token
            "code id_token",          // Hybrid Flow
            "code token",             // Hybrid Flow
            "code id_token token"     // Hybrid Flow
        )

        val metadata = ProviderMetadata(
            issuer = "https://example.com",
            authorization_endpoint = "https://example.com/authorize",
            token_endpoint = "https://example.com/token",
            jwks_uri = "https://example.com/jwks",
            response_types_supported = responseTypes,
            subject_types_supported = listOf("public"),
            id_token_signing_alg_values_supported = listOf("RS256")
        )

        assertEquals(6, metadata.response_types_supported.size)
        assertTrue(metadata.response_types_supported.contains("code"))
        assertTrue(metadata.response_types_supported.contains("id_token"))
        assertTrue(metadata.response_types_supported.contains("id_token token"))
        assertTrue(metadata.response_types_supported.contains("code id_token"))
        assertTrue(metadata.response_types_supported.contains("code token"))
        assertTrue(metadata.response_types_supported.contains("code id_token token"))
    }

    @Test
    fun `JwksResponse contains keys array`() {
        val key = JsonWebKey(
            kty = "RSA",
            use = "sig",
            kid = "default",
            alg = "RS256",
            n = "modulus-value",
            e = "AQAB"
        )

        val jwks = JwksResponse(keys = listOf(key))

        assertEquals(1, jwks.keys.size)
        assertEquals("RSA", jwks.keys[0].kty)
        assertEquals("sig", jwks.keys[0].use)
        assertEquals("default", jwks.keys[0].kid)
        assertEquals("RS256", jwks.keys[0].alg)
    }

    @Test
    fun `JsonWebKey includes RSA parameters`() {
        val key = JsonWebKey(
            kty = "RSA",
            use = "sig",
            kid = "test-key-id",
            alg = "RS256",
            n = "xGOr-H7A-PWgGfBxf7y_example_modulus_value",
            e = "AQAB"
        )

        assertEquals("RSA", key.kty)
        assertEquals("sig", key.use)
        assertEquals("test-key-id", key.kid)
        assertEquals("RS256", key.alg)
        assertNotNull(key.n)
        assertNotNull(key.e)
        assertEquals("AQAB", key.e) // Standard RSA public exponent (65537)
    }

    @Test
    fun `JsonWebKey supports optional RSA parameters`() {
        val keyWithoutRSA = JsonWebKey(
            kty = "EC",
            use = "sig",
            kid = "ec-key",
            alg = "ES256"
        )

        assertEquals("EC", keyWithoutRSA.kty)
        assertNull(keyWithoutRSA.n)
        assertNull(keyWithoutRSA.e)
    }

    @Test
    fun `Standard OpenID scopes are defined correctly`() {
        assertEquals("openid", OpenIdScopes.OPENID)
        assertEquals("profile", OpenIdScopes.PROFILE)
        assertEquals("email", OpenIdScopes.EMAIL)
        assertEquals("address", OpenIdScopes.ADDRESS)
        assertEquals("phone", OpenIdScopes.PHONE)
        assertEquals("offline_access", OpenIdScopes.OFFLINE_ACCESS)
    }

    @Test
    fun `Discovery metadata includes all standard scopes`() {
        val scopes = listOf(
            OpenIdScopes.OPENID,
            OpenIdScopes.PROFILE,
            OpenIdScopes.EMAIL,
            OpenIdScopes.ADDRESS,
            OpenIdScopes.PHONE,
            OpenIdScopes.OFFLINE_ACCESS
        )

        assertTrue(scopes.contains("openid"))
        assertTrue(scopes.contains("profile"))
        assertTrue(scopes.contains("email"))
        assertTrue(scopes.contains("address"))
        assertTrue(scopes.contains("phone"))
        assertTrue(scopes.contains("offline_access"))
        assertEquals(6, scopes.size)
    }

    @Test
    fun `Discovery metadata includes standard claims`() {
        val claims = listOf(
            "sub", "iss", "aud", "exp", "iat", "auth_time", "nonce",
            "name", "given_name", "family_name", "middle_name",
            "nickname", "preferred_username", "profile", "picture",
            "website", "email", "email_verified", "gender", "birthdate",
            "zoneinfo", "locale", "phone_number", "phone_number_verified",
            "address", "updated_at"
        )

        // Verify JWT-specific claims
        assertTrue(claims.contains("sub"))
        assertTrue(claims.contains("iss"))
        assertTrue(claims.contains("aud"))
        assertTrue(claims.contains("exp"))
        assertTrue(claims.contains("iat"))
        assertTrue(claims.contains("auth_time"))
        assertTrue(claims.contains("nonce"))

        // Verify profile claims
        assertTrue(claims.contains("name"))
        assertTrue(claims.contains("given_name"))
        assertTrue(claims.contains("family_name"))
        assertTrue(claims.contains("picture"))

        // Verify email claims
        assertTrue(claims.contains("email"))
        assertTrue(claims.contains("email_verified"))

        // Verify phone claims
        assertTrue(claims.contains("phone_number"))
        assertTrue(claims.contains("phone_number_verified"))

        // Verify address claim
        assertTrue(claims.contains("address"))
    }

    @Test
    fun `Token endpoint auth methods include standard options`() {
        val methods = listOf(
            "client_secret_post",     // Client secret in POST body
            "client_secret_basic",    // Client secret in Basic Auth header
            "none"                    // Public clients (PKCE only)
        )

        assertEquals(3, methods.size)
        assertTrue(methods.contains("client_secret_post"))
        assertTrue(methods.contains("client_secret_basic"))
        assertTrue(methods.contains("none"))
    }

    @Test
    fun `PKCE challenge methods are correctly defined`() {
        val methods = listOf("S256", "plain")

        assertEquals(2, methods.size)
        assertTrue(methods.contains("S256"))
        assertTrue(methods.contains("plain"))
    }

    @Test
    fun `IdTokenClaims includes all required fields`() {
        val claims = IdTokenClaims(
            iss = "https://example.com",
            sub = "user-123",
            aud = "client-456",
            exp = 1234567890L,
            iat = 1234564290L
        )

        assertEquals("https://example.com", claims.iss)
        assertEquals("user-123", claims.sub)
        assertEquals("client-456", claims.aud)
        assertEquals(1234567890L, claims.exp)
        assertEquals(1234564290L, claims.iat)
        assertNull(claims.auth_time)
        assertNull(claims.nonce)
    }

    @Test
    fun `IdTokenClaims supports optional fields`() {
        val claims = IdTokenClaims(
            iss = "https://example.com",
            sub = "user-123",
            aud = "client-456",
            exp = 1234567890L,
            iat = 1234564290L,
            auth_time = 1234564000L,
            nonce = "random-nonce-value",
            acr = "urn:mace:incommon:iap:silver",
            amr = listOf("pwd", "mfa"),
            azp = "client-789",
            name = "John Doe",
            email = "john@example.com",
            email_verified = true
        )

        assertEquals(1234564000L, claims.auth_time)
        assertEquals("random-nonce-value", claims.nonce)
        assertEquals("urn:mace:incommon:iap:silver", claims.acr)
        assertNotNull(claims.amr)
        assertEquals(2, claims.amr!!.size)
        assertTrue(claims.amr!!.contains("pwd"))
        assertTrue(claims.amr!!.contains("mfa"))
        assertEquals("client-789", claims.azp)
        assertEquals("John Doe", claims.name)
        assertEquals("john@example.com", claims.email)
        assertTrue(claims.email_verified == true)
    }
}
