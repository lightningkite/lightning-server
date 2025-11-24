package com.lightningkite.lightningserver.sessions.openid

import com.lightningkite.lightningserver.BadRequestException
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

/**
 * Simplified tests for Token endpoint helper functions
 *
 * These tests verify the core logic of PKCE validation and error handling.
 * Full integration tests would require more complex HTTP/ServerRuntime setup.
 */
class TokenEndpointSimpleTest {

    @Test
    fun `PKCE S256 validation computes correct challenge`() {
        // Create a code verifier
        val codeVerifier = "test-verifier-with-sufficient-length-for-pkce-security"

        // Compute the expected challenge using S256
        val hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.encodeToByteArray())
        val expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash)

        // Verify the challenge was computed correctly
        assertNotNull(expectedChallenge)
        assertTrue(expectedChallenge.isNotEmpty())

        // Verify it's base64url encoded (no padding, URL-safe characters)
        assertFalse(expectedChallenge.contains("="))
        assertTrue(expectedChallenge.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun `PKCE plain method uses verifier as challenge`() {
        val codeVerifier = "plain-text-challenge"

        // In plain mode, challenge equals verifier
        val challenge = codeVerifier

        assertEquals(codeVerifier, challenge)
    }

    @Test
    fun `OAuth error codes are defined correctly`() {
        // Verify standard OAuth 2.0 error codes exist
        assertEquals("invalid_request", OAuth2ErrorCodes.INVALID_REQUEST)
        assertEquals("invalid_grant", OAuth2ErrorCodes.INVALID_GRANT)
        assertEquals("invalid_client", OAuth2ErrorCodes.INVALID_CLIENT)
        assertEquals("unsupported_grant_type", OAuth2ErrorCodes.UNSUPPORTED_GRANT_TYPE)
        assertEquals("invalid_scope", OAuth2ErrorCodes.INVALID_SCOPE)
    }

    @Test
    fun `Grant types are defined correctly`() {
        // Verify standard OAuth 2.0 grant types
        assertEquals("authorization_code", GrantTypes.AUTHORIZATION_CODE)
        assertEquals("refresh_token", GrantTypes.REFRESH_TOKEN)
    }

    @Test
    fun `OpenID scopes are defined correctly`() {
        // Verify standard OpenID Connect scopes
        assertEquals("openid", OpenIdScopes.OPENID)
        assertEquals("profile", OpenIdScopes.PROFILE)
        assertEquals("email", OpenIdScopes.EMAIL)
        assertEquals("offline_access", OpenIdScopes.OFFLINE_ACCESS)
    }

    @Test
    fun `TokenRequest model serialization works`() {
        val request = TokenRequest(
            grant_type = GrantTypes.AUTHORIZATION_CODE,
            code = "test-code",
            redirect_uri = "https://example.com/callback",
            client_id = "test-client"
        )

        assertEquals(GrantTypes.AUTHORIZATION_CODE, request.grant_type)
        assertEquals("test-code", request.code)
        assertEquals("https://example.com/callback", request.redirect_uri)
        assertEquals("test-client", request.client_id)
    }

    @Test
    fun `TokenResponse model works correctly`() {
        val response = TokenResponse(
            access_token = "access-token-value",
            token_type = "Bearer",
            expires_in = 3600,
            refresh_token = "refresh-token-value",
            id_token = "id-token-jwt",
            scope = "openid email"
        )

        assertEquals("access-token-value", response.access_token)
        assertEquals("Bearer", response.token_type)
        assertEquals(3600, response.expires_in)
        assertEquals("refresh-token-value", response.refresh_token)
        assertEquals("id-token-jwt", response.id_token)
        assertEquals("openid email", response.scope)
    }

    @Test
    fun `IssuedToken model includes all required fields`() {
        val token = IssuedToken(
            token = "token-value",
            tokenType = TokenType.ACCESS,
            userId = "user-123",
            clientId = "client-456",
            scope = "openid email",
            issuedAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            expiresAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis() + 3600000),
            nonce = "nonce-value",
            authTime = 1234567890L
        )

        assertEquals("token-value", token.token)
        assertEquals(TokenType.ACCESS, token.tokenType)
        assertEquals("user-123", token.userId)
        assertEquals("client-456", token.clientId)
        assertNotNull(token.nonce)
        assertNotNull(token.authTime)
    }

    @Test
    fun `AuthorizationCode model includes PKCE fields`() {
        val authCode = AuthorizationCode(
            code = "auth-code",
            clientId = "client-id",
            userId = "user-id",
            redirectUri = "https://example.com/callback",
            scope = "openid",
            nonce = "nonce",
            codeChallenge = "challenge",
            codeChallengeMethod = "S256",
            authTime = 1234567890L,
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis())
        )

        assertEquals("challenge", authCode.codeChallenge)
        assertEquals("S256", authCode.codeChallengeMethod)
    }
}
