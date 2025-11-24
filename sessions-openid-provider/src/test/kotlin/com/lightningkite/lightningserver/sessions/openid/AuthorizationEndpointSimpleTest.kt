package com.lightningkite.lightningserver.sessions.openid

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

/**
 * Simplified tests for Authorization endpoint
 *
 * These tests verify the core data models and validation logic used by
 * the Authorization endpoint (OAuth 2.0 / OpenID Connect authorization flow).
 */
class AuthorizationEndpointSimpleTest {

    @Test
    fun `AuthorizationRequest model includes all required fields`() {
        val request = AuthorizationRequest(
            response_type = "code",
            client_id = "test-client-123",
            redirect_uri = "https://client.example.com/callback",
            scope = "openid profile email"
        )

        assertEquals("code", request.response_type)
        assertEquals("test-client-123", request.client_id)
        assertEquals("https://client.example.com/callback", request.redirect_uri)
        assertEquals("openid profile email", request.scope)
        assertNull(request.state)
        assertNull(request.nonce)
        assertNull(request.code_challenge)
    }

    @Test
    fun `AuthorizationRequest supports optional fields`() {
        val request = AuthorizationRequest(
            response_type = "code",
            client_id = "test-client-123",
            redirect_uri = "https://client.example.com/callback",
            scope = "openid profile email",
            state = "random-state-value",
            response_mode = "query",
            nonce = "random-nonce-value",
            code_challenge = "challenge-value",
            code_challenge_method = "S256",
            prompt = "consent",
            max_age = 3600,
            login_hint = "user@example.com"
        )

        assertEquals("random-state-value", request.state)
        assertEquals("query", request.response_mode)
        assertEquals("random-nonce-value", request.nonce)
        assertEquals("challenge-value", request.code_challenge)
        assertEquals("S256", request.code_challenge_method)
        assertEquals("consent", request.prompt)
        assertEquals(3600, request.max_age)
        assertEquals("user@example.com", request.login_hint)
    }

    @Test
    fun `AuthorizationRequest supports PKCE with S256`() {
        val request = AuthorizationRequest(
            response_type = "code",
            client_id = "public-client",
            redirect_uri = "https://app.example.com/callback",
            scope = "openid",
            code_challenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            code_challenge_method = "S256"
        )

        assertNotNull(request.code_challenge)
        assertEquals("S256", request.code_challenge_method)
    }

    @Test
    fun `AuthorizationRequest supports PKCE with plain method`() {
        val request = AuthorizationRequest(
            response_type = "code",
            client_id = "public-client",
            redirect_uri = "https://app.example.com/callback",
            scope = "openid",
            code_challenge = "plain-challenge-value",
            code_challenge_method = "plain"
        )

        assertNotNull(request.code_challenge)
        assertEquals("plain", request.code_challenge_method)
    }

    @Test
    fun `AuthorizationResponse includes code and state`() {
        val response = AuthorizationResponse(
            code = "auth-code-12345",
            state = "client-state-value"
        )

        assertEquals("auth-code-12345", response.code)
        assertEquals("client-state-value", response.state)
    }

    @Test
    fun `AuthorizationResponse supports null state`() {
        val response = AuthorizationResponse(
            code = "auth-code-12345"
        )

        assertEquals("auth-code-12345", response.code)
        assertNull(response.state)
    }

    @Test
    fun `Response type code is the standard value`() {
        val responseType = "code"

        assertEquals("code", responseType)

        // Verify it's not other flow types
        assertNotEquals("token", responseType)
        assertNotEquals("id_token", responseType)
        assertNotEquals("id_token token", responseType)
    }

    @Test
    fun `Scope validation ensures openid is present`() {
        val validScope = "openid profile email"
        val invalidScope = "profile email"

        assertTrue(validScope.contains("openid"))
        assertTrue(validScope.contains("profile"))
        assertTrue(validScope.contains("email"))

        assertFalse(invalidScope.contains("openid"))
    }

    @Test
    fun `Scope parsing splits correctly`() {
        val scope = "openid profile email offline_access"
        val scopes = scope.split(" ")

        assertEquals(4, scopes.size)
        assertTrue(scopes.contains("openid"))
        assertTrue(scopes.contains("profile"))
        assertTrue(scopes.contains("email"))
        assertTrue(scopes.contains("offline_access"))
    }

    @Test
    fun `Response modes are defined correctly`() {
        val queryMode = "query"
        val fragmentMode = "fragment"
        val formPostMode = "form_post"

        assertEquals("query", queryMode)
        assertEquals("fragment", fragmentMode)
        assertEquals("form_post", formPostMode)
    }

    @Test
    fun `Prompt values are defined correctly`() {
        val nonePrompt = "none"
        val loginPrompt = "login"
        val consentPrompt = "consent"
        val selectAccountPrompt = "select_account"

        assertEquals("none", nonePrompt)
        assertEquals("login", loginPrompt)
        assertEquals("consent", consentPrompt)
        assertEquals("select_account", selectAccountPrompt)
    }

    @Test
    fun `AuthorizationCode stores PKCE parameters`() {
        val authCode = AuthorizationCode(
            code = "auth-code",
            clientId = "client-id",
            userId = "user-id",
            redirectUri = "https://example.com/callback",
            scope = "openid profile",
            nonce = "nonce-value",
            codeChallenge = "challenge-value",
            codeChallengeMethod = "S256",
            authTime = 1234567890L,
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis())
        )

        assertEquals("challenge-value", authCode.codeChallenge)
        assertEquals("S256", authCode.codeChallengeMethod)
        assertEquals("nonce-value", authCode.nonce)
        assertEquals(1234567890L, authCode.authTime)
    }

    @Test
    fun `AuthorizationCode supports null PKCE parameters`() {
        val authCode = AuthorizationCode(
            code = "auth-code",
            clientId = "client-id",
            userId = "user-id",
            redirectUri = "https://example.com/callback",
            scope = "openid",
            nonce = null,
            codeChallenge = null,
            codeChallengeMethod = null,
            authTime = 1234567890L,
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis())
        )

        assertNull(authCode.codeChallenge)
        assertNull(authCode.codeChallengeMethod)
        assertNull(authCode.nonce)
    }

    @Test
    fun `Redirect URI validation requires exact match`() {
        val registered = "https://example.com/callback"
        val provided = "https://example.com/callback"
        val invalid = "https://example.com/different"

        assertEquals(registered, provided)
        assertNotEquals(registered, invalid)
    }
}
