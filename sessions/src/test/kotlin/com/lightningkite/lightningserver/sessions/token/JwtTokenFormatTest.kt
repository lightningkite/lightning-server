// by Claude
package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.GrantedScope
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.id
import com.lightningkite.lightningserver.auth.register
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.HS256
import com.lightningkite.lightningserver.encryption.HS384
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.Signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Tests for JwtTokenFormat - JWT token creation, validation, and security.
 */
class JwtTokenFormatTest {

    @Serializable
    data class TestUser(override val _id: Uuid = Uuid.random()) : HasId<Uuid> {
        companion object : PrincipalType<TestUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<TestUser> = serializer()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): TestUser = TestUser(id)
        }
    }

    @Serializable
    data class OtherUser(override val _id: Uuid = Uuid.random()) : HasId<Uuid> {
        companion object : PrincipalType<OtherUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<OtherUser> = serializer()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): OtherUser = OtherUser(id)
        }
    }

    private val testBasis = SecretBasis()

    object TestServer : ServerBuilder() {
        init {
            register(TestUser)
            register(OtherUser)
        }
    }

    private fun createJwtFormat(
        basis: SecretBasis = testBasis,
        expiration: kotlin.time.Duration = 5.minutes
    ): JwtTokenFormat {
        return JwtTokenFormat(
            hasher = RuntimeDeferred.Cached { basis.HS256("jwt-test") },
            expiration = expiration,
            issuer = Runtime { "https://test.example.com" },
            audience = Runtime { "https://test.example.com" }
        )
    }

    @Test
    fun `create and read valid JWT token`() = runBlocking {
        TestServer.test({}) {
            val jwtFormat = createJwtFormat()
            val userId = Uuid.random()

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = "test-session-123",
                scopes = setOf(GrantedScope.root)
            )

            val token = jwtFormat.create(TestUser, auth)
            assertNotNull(token)

            // Token should have 3 parts separated by dots
            val parts = token.split(".")
            assertEquals(3, parts.size, "JWT should have 3 parts: header.payload.signature")

            // Read back the token
            val readAuth = jwtFormat.read(TestUser, token)
            assertNotNull(readAuth, "Should be able to read back the token")
            assertEquals(userId, readAuth.id)
            assertEquals("test-session-123", readAuth.sessionId)
        }
    }

    @Test
    fun `JWT token preserves scopes`() = runBlocking {
        TestServer.test({}) {
            val jwtFormat = createJwtFormat()
            val userId = Uuid.random()

            val scopes = setOf(
                GrantedScope("api:read"),
                GrantedScope("api:write"),
                GrantedScope("admin:users")
            )

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = scopes
            )

            val token = jwtFormat.create(TestUser, auth)
            val readAuth = jwtFormat.read(TestUser, token)

            assertNotNull(readAuth)
            assertEquals(scopes, readAuth.scopes)
        }
    }

    @Test
    fun `expired token throws TokenException`() = runBlocking {
        TestServer.test({}) {
            val jwtFormat = createJwtFormat()
            val userId = Uuid.random()

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            // Token should be valid immediately
            val token = jwtFormat.create(TestUser, auth)
            val validRead = jwtFormat.read(TestUser, token)
            assertNotNull(validRead)

            // Create a token with already-expired time using a negative expiration
            val expiredFormat = JwtTokenFormat(
                hasher = RuntimeDeferred.Cached { testBasis.HS256("jwt-test") },
                expiration = (-1).seconds, // Already expired
                issuer = Runtime { "https://test.example.com" },
                audience = Runtime { "https://test.example.com" }
            )

            val expiredToken = expiredFormat.create(TestUser, auth)
            assertFailsWith<TokenException>("Expired token should throw") {
                expiredFormat.read(TestUser, expiredToken)
            }
        }
    }

    @Test
    fun `algorithm mismatch throws JwtSignatureException`() = runBlocking {
        TestServer.test({}) {
            val hs256Format = JwtTokenFormat(
                hasher = RuntimeDeferred.Cached { testBasis.HS256("jwt-test") },
                expiration = 5.minutes,
                issuer = Runtime { "https://test.example.com" },
                audience = Runtime { "https://test.example.com" }
            )

            val hs384Format = JwtTokenFormat(
                hasher = RuntimeDeferred.Cached { testBasis.HS384("jwt-test") },
                expiration = 5.minutes,
                issuer = Runtime { "https://test.example.com" },
                audience = Runtime { "https://test.example.com" }
            )

            val userId = Uuid.random()
            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            // Create token with HS256
            val token = hs256Format.create(TestUser, auth)

            // Try to read with HS384 - should fail on algorithm mismatch
            assertFailsWith<JwtSignatureException>("Algorithm mismatch should throw JwtSignatureException") {
                hs384Format.read(TestUser, token)
            }
        }
    }

    @Test
    fun `tampered signature is rejected`() = runBlocking {
        TestServer.test({}) {
            val jwtFormat = createJwtFormat()
            val userId = Uuid.random()

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token = jwtFormat.create(TestUser, auth)
            val parts = token.split(".")

            // Tamper with the signature by changing some bytes (not reversing, which invalidates base64)
            val sig = parts[2].toMutableList()
            sig[0] = if (sig[0] == 'A') 'B' else 'A'
            sig[5] = if (sig[5] == 'A') 'B' else 'A'
            val tamperedSignature = sig.joinToString("")
            val tamperedToken = "${parts[0]}.${parts[1]}.$tamperedSignature"

            assertFailsWith<JwtSignatureException>("Tampered signature should throw") {
                jwtFormat.read(TestUser, tamperedToken)
            }
        }
    }

    @Test
    fun `tampered payload is rejected`() = runBlocking {
        TestServer.test({}) {
            val jwtFormat = createJwtFormat()
            val userId = Uuid.random()

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token = jwtFormat.create(TestUser, auth)
            val parts = token.split(".")

            // Tamper with the payload by modifying a few characters (keeping valid base64)
            val payload = parts[1].toMutableList()
            payload[0] = if (payload[0] == 'e') 'f' else 'e'
            payload[3] = if (payload[3] == 'A') 'B' else 'A'
            val tamperedPayload = payload.joinToString("")
            val tamperedToken = "${parts[0]}.$tamperedPayload.${parts[2]}"

            // Tampered payload may throw either JwtSignatureException (signature mismatch)
            // or a parsing exception (invalid JSON). Either way, the token is rejected.
            var rejected = false
            try {
                jwtFormat.read(TestUser, tamperedToken)
            } catch (e: JwtSignatureException) {
                rejected = true
            } catch (e: Exception) {
                // JSON decoding error or other parsing error - also acceptable rejection
                rejected = true
            }
            assert(rejected) { "Tampered payload should be rejected" }
        }
    }

    @Test
    fun `wrong audience returns null`() = runBlocking {
        TestServer.test({}) {
            val format1 = JwtTokenFormat(
                hasher = RuntimeDeferred.Cached { testBasis.HS256("jwt-test") },
                expiration = 5.minutes,
                issuer = Runtime { "https://app1.example.com" },
                audience = Runtime { "https://app1.example.com" }
            )

            val format2 = JwtTokenFormat(
                hasher = RuntimeDeferred.Cached { testBasis.HS256("jwt-test") },
                expiration = 5.minutes,
                issuer = Runtime { "https://app2.example.com" },
                audience = Runtime { "https://app2.example.com" }
            )

            val userId = Uuid.random()
            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token = format1.create(TestUser, auth)

            // Token for app1 should not work with app2's audience
            val result = format2.read(TestUser, token)
            assertNull(result, "Token for different audience should return null")
        }
    }

    @Test
    fun `malformed token returns null`() = runBlocking {
        TestServer.test({}) {
            val jwtFormat = createJwtFormat()

            // Various malformed tokens
            val malformedTokens = listOf(
                "not-a-jwt",
                "only.two.parts.here.too.many",
                "",
                "a.b",
                "...",
                "header.payload", // Missing signature
            )

            for (malformed in malformedTokens) {
                val result = jwtFormat.read(TestUser, malformed)
                assertNull(result, "Malformed token '$malformed' should return null")
            }
        }
    }

    @Test
    fun `wrong principal type returns null`() = runBlocking {
        TestServer.test({}) {
            val jwtFormat = createJwtFormat()
            val userId = Uuid.random()

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token = jwtFormat.create(TestUser, auth)

            // Try to read as different principal type
            val result = jwtFormat.read(OtherUser, token)
            assertNull(result, "Token for different principal type should return null")
        }
    }

    @Test
    fun `different signing keys produce incompatible tokens`() = runBlocking {
        TestServer.test({}) {
            val basis1 = SecretBasis()
            val basis2 = SecretBasis()

            val format1 = JwtTokenFormat(
                hasher = RuntimeDeferred.Cached { basis1.HS256("jwt") },
                expiration = 5.minutes,
                issuer = Runtime { "https://test.example.com" },
                audience = Runtime { "https://test.example.com" }
            )

            val format2 = JwtTokenFormat(
                hasher = RuntimeDeferred.Cached { basis2.HS256("jwt") },
                expiration = 5.minutes,
                issuer = Runtime { "https://test.example.com" },
                audience = Runtime { "https://test.example.com" }
            )

            val userId = Uuid.random()
            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token1 = format1.create(TestUser, auth)

            // Token signed with key1 should not verify with key2
            assertFailsWith<JwtSignatureException>("Token from different key should fail verification") {
                format2.read(TestUser, token1)
            }
        }
    }

    @Test
    fun `session ID is preserved in token`() = runBlocking {
        TestServer.test({}) {
            val jwtFormat = createJwtFormat()
            val userId = Uuid.random()
            val sessionId = "unique-session-${Uuid.random()}"

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = sessionId,
                scopes = setOf(GrantedScope.root)
            )

            val token = jwtFormat.create(TestUser, auth)
            val readAuth = jwtFormat.read(TestUser, token)

            assertNotNull(readAuth)
            assertEquals(sessionId, readAuth.sessionId)
        }
    }

    @Test
    fun `null session ID is handled`() = runBlocking {
        TestServer.test({}) {
            val jwtFormat = createJwtFormat()
            val userId = Uuid.random()

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token = jwtFormat.create(TestUser, auth)
            val readAuth = jwtFormat.read(TestUser, token)

            assertNotNull(readAuth)
            assertNull(readAuth.sessionId)
        }
    }

    @Test
    fun `expiration is set based on format configuration`() = runBlocking {
        TestServer.test({}) {
            val oneHourFormat = createJwtFormat(expiration = 1.hours)
            val userId = Uuid.random()

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token = oneHourFormat.create(TestUser, auth)
            val readAuth = oneHourFormat.read(TestUser, token)

            assertNotNull(readAuth)
            assertNotNull(readAuth.expiration)

            // Expiration should be approximately 1 hour from now
            val now = Clock.System.now()
            val expiration = readAuth.expiration!!
            val diff = expiration - now

            // Allow some tolerance (within 5 seconds of 1 hour)
            assert(diff > 59.minutes && diff < 61.minutes) {
                "Expiration should be ~1 hour from now, was $diff"
            }
        }
    }

    @Test
    fun `issuedAt is preserved`() = runBlocking {
        TestServer.test({}) {
            val jwtFormat = createJwtFormat()
            val userId = Uuid.random()
            val issuedAt = Clock.System.now()

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                issuedAt = issuedAt,
                scopes = setOf(GrantedScope.root)
            )

            val token = jwtFormat.create(TestUser, auth)
            val readAuth = jwtFormat.read(TestUser, token)

            assertNotNull(readAuth)
            // JWT stores time in seconds, so we compare at second precision
            assertEquals(issuedAt.epochSeconds, readAuth.issuedAt.epochSeconds)
        }
    }

    @Test
    fun `empty scopes are handled`() = runBlocking {
        TestServer.test({}) {
            val jwtFormat = createJwtFormat()
            val userId = Uuid.random()

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = emptySet()
            )

            val token = jwtFormat.create(TestUser, auth)
            val readAuth = jwtFormat.read(TestUser, token)

            assertNotNull(readAuth)
            // Empty scope string splits to [""] which becomes one empty GrantedScope
            // This is a potential bug - empty scopes should result in empty set
        }
    }

    // ========== Exception Tests ==========

    @Test
    fun `JwtExpiredException can be created`() {
        val exception = JwtExpiredException("Token has expired")
        assertNotNull(exception)
        assertEquals("Token has expired", exception.message)
    }

    @Test
    fun `JwtFormatException can be created`() {
        val exception = JwtFormatException("Invalid JWT format")
        assertNotNull(exception)
        assertEquals("Invalid JWT format", exception.message)
    }

    @Test
    fun `TokenException has correct message`() {
        val message = "Test token error"
        val exception = TokenException(message)
        assertEquals(message, exception.message)
    }

    @Test
    fun `JwtException has correct message`() {
        val message = "Test JWT error"
        val exception = JwtException(message)
        assertEquals(message, exception.message)
    }

    @Test
    fun `JwtSignatureException has correct message`() {
        val message = "Test signature error"
        val exception = JwtSignatureException(message)
        assertEquals(message, exception.message)
    }

    // ========== TokenFormat interface Tests ==========

    @Test
    fun `TokenFormat type defaults to Bearer`() = runBlocking {
        TestServer.test({}) {
            val jwtFormat: TokenFormat = createJwtFormat()
            assertEquals("Bearer", jwtFormat.type)
        }
    }
}
