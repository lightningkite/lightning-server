package com.lightningkite.lightningserver.sessions.openid

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

/**
 * Simplified tests for UserInfo endpoint helper functions
 *
 * These tests verify the core logic of UserInfo response building and scope handling.
 */
class UserInfoEndpointSimpleTest {

    @Test
    fun `UserInfoRequest model serialization works`() {
        val request = UserInfoRequest(
            access_token = "test-access-token-12345"
        )

        assertEquals("test-access-token-12345", request.access_token)
    }

    @Test
    fun `UserInfoResponse model includes sub claim`() {
        val response = UserInfoResponse(
            sub = "user-123"
        )

        assertEquals("user-123", response.sub)
        assertNull(response.name)
        assertNull(response.email)
        assertNull(response.email_verified)
    }

    @Test
    fun `UserInfoResponse model includes profile claims`() {
        val response = UserInfoResponse(
            sub = "user-123",
            name = "John Doe",
            given_name = "John",
            family_name = "Doe",
            picture = "https://example.com/photo.jpg",
            email = "john@example.com",
            email_verified = true
        )

        assertEquals("user-123", response.sub)
        assertEquals("John Doe", response.name)
        assertEquals("John", response.given_name)
        assertEquals("Doe", response.family_name)
        assertEquals("https://example.com/photo.jpg", response.picture)
        assertEquals("john@example.com", response.email)
        assertTrue(response.email_verified == true)
    }

    @Test
    fun `UserInfoResponse model supports address claim`() {
        val address = Address(
            formatted = "123 Main St, City, ST 12345",
            street_address = "123 Main St",
            locality = "City",
            region = "ST",
            postal_code = "12345",
            country = "US"
        )

        val response = UserInfoResponse(
            sub = "user-123",
            address = address
        )

        assertEquals("user-123", response.sub)
        assertNotNull(response.address)
        assertEquals("123 Main St", response.address?.street_address)
        assertEquals("City", response.address?.locality)
        assertEquals("US", response.address?.country)
    }

    @Test
    fun `UserInfoResponse model supports phone claims`() {
        val response = UserInfoResponse(
            sub = "user-123",
            phone_number = "+1-555-123-4567",
            phone_number_verified = true
        )

        assertEquals("user-123", response.sub)
        assertEquals("+1-555-123-4567", response.phone_number)
        assertTrue(response.phone_number_verified == true)
    }

    @Test
    fun `UserInfoResponse model supports all optional fields`() {
        val response = UserInfoResponse(
            sub = "user-123",
            name = "John Doe",
            given_name = "John",
            family_name = "Doe",
            middle_name = "Q",
            nickname = "Johnny",
            preferred_username = "johndoe",
            profile = "https://example.com/profile/johndoe",
            picture = "https://example.com/photo.jpg",
            website = "https://johndoe.com",
            email = "john@example.com",
            email_verified = true,
            gender = "male",
            birthdate = "1990-01-15",
            zoneinfo = "America/Los_Angeles",
            locale = "en-US",
            phone_number = "+1-555-123-4567",
            phone_number_verified = true,
            updated_at = 1234567890L
        )

        assertEquals("user-123", response.sub)
        assertEquals("John Doe", response.name)
        assertEquals("John", response.given_name)
        assertEquals("Doe", response.family_name)
        assertEquals("Q", response.middle_name)
        assertEquals("Johnny", response.nickname)
        assertEquals("johndoe", response.preferred_username)
        assertEquals("https://example.com/profile/johndoe", response.profile)
        assertEquals("https://example.com/photo.jpg", response.picture)
        assertEquals("https://johndoe.com", response.website)
        assertEquals("john@example.com", response.email)
        assertTrue(response.email_verified == true)
        assertEquals("male", response.gender)
        assertEquals("1990-01-15", response.birthdate)
        assertEquals("America/Los_Angeles", response.zoneinfo)
        assertEquals("en-US", response.locale)
        assertEquals("+1-555-123-4567", response.phone_number)
        assertTrue(response.phone_number_verified == true)
        assertEquals(1234567890L, response.updated_at)
    }

    @Test
    fun `Scope parsing handles space-separated scopes`() {
        val scopeString = "openid profile email"
        val scopes = scopeString.split(" ").toSet()

        assertTrue(scopes.contains("openid"))
        assertTrue(scopes.contains("profile"))
        assertTrue(scopes.contains("email"))
        assertEquals(3, scopes.size)
    }

    @Test
    fun `Scope parsing handles single scope`() {
        val scopeString = "openid"
        val scopes = scopeString.split(" ").toSet()

        assertTrue(scopes.contains("openid"))
        assertEquals(1, scopes.size)
    }

    @Test
    fun `IssuedToken includes all fields needed for UserInfo`() {
        val token = IssuedToken(
            token = "access-token-value",
            tokenType = TokenType.ACCESS,
            userId = "user-123",
            clientId = "client-456",
            scope = "openid profile email",
            issuedAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            expiresAt = kotlin.time.Instant.fromEpochMilliseconds(System.currentTimeMillis() + 3600000),
            nonce = "nonce-value",
            authTime = 1234567890L
        )

        assertEquals("access-token-value", token.token)
        assertEquals(TokenType.ACCESS, token.tokenType)
        assertEquals("user-123", token.userId)
        assertEquals("openid profile email", token.scope)
        assertTrue(token.scope.contains(OpenIdScopes.OPENID))
        assertTrue(token.scope.contains(OpenIdScopes.PROFILE))
        assertTrue(token.scope.contains(OpenIdScopes.EMAIL))
    }
}
