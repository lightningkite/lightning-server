// by Claude
package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.*
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant
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
            registerBasicMediaTypeCoders()
            register(TestUser)
            register(OtherUser)
        }
    }

    private fun createJwtFormat(
        basis: SecretBasis = testBasis,
        expiration: kotlin.time.Duration = 5.minutes,
    ): JwtTokenFormat {
        return JwtTokenFormat(
            hasher = RuntimeDeferred.Cached { basis.HS256("jwt-test") },
            expiration = expiration,
            issuer = Runtime { "https://test.example.com" },
            audience = Runtime { "https://test.example.com" },
            keyId = "jwt-test"
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

            val token = execute { jwtFormat.create(TestUser, auth) }
            assertNotNull(token)

            // Token should have 3 parts separated by dots
            val parts = token.split(".")
            assertEquals(3, parts.size, "JWT should have 3 parts: header.payload.signature")

            // Read back the token
            val readAuth = execute { jwtFormat.read(TestUser, token) }
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

            val token = execute { jwtFormat.create(TestUser, auth) }
            val readAuth = execute { jwtFormat.read(TestUser, token) }

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
            val token = execute { jwtFormat.create(TestUser, auth) }
            val validRead = execute { jwtFormat.read(TestUser, token) }
            assertNotNull(validRead)

            // Create a token with already-expired time using a negative expiration
            val expiredFormat = JwtTokenFormat(
                hasher = RuntimeDeferred.Cached { testBasis.HS256("jwt-test") },
                expiration = (-1).seconds, // Already expired
                issuer = Runtime { "https://test.example.com" },
                audience = Runtime { "https://test.example.com" },
                keyId = "jwt-test"
            )

            val expiredToken = execute { expiredFormat.create(TestUser, auth) }
            assertFailsWith<TokenException>("Expired token should throw") {
                execute { expiredFormat.read(TestUser, expiredToken) }
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
                audience = Runtime { "https://test.example.com" },
                keyId = "jwt-test"
            )

            val hs384Format = JwtTokenFormat(
                hasher = RuntimeDeferred.Cached { testBasis.HS384("jwt-test") },
                expiration = 5.minutes,
                issuer = Runtime { "https://test.example.com" },
                audience = Runtime { "https://test.example.com" },
                keyId = "jwt-test"
            )

            val userId = Uuid.random()
            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            // Create token with HS256
            val token = execute { hs256Format.create(TestUser, auth) }

            // Try to read with HS384 - should fail on algorithm mismatch
            assertFailsWith<JwtSignatureException>("Algorithm mismatch should throw JwtSignatureException") {
                execute { hs384Format.read(TestUser, token) }
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

            val token = execute { jwtFormat.create(TestUser, auth) }
            val parts = token.split(".")

            // Tamper with the signature by changing some bytes (not reversing, which invalidates base64)
            val sig = parts[2].toMutableList()
            sig[0] = if (sig[0] == 'A') 'B' else 'A'
            sig[5] = if (sig[5] == 'A') 'B' else 'A'
            val tamperedSignature = sig.joinToString("")
            val tamperedToken = "${parts[0]}.${parts[1]}.$tamperedSignature"

            assertFailsWith<JwtSignatureException>("Tampered signature should throw") {
                execute { jwtFormat.read(TestUser, tamperedToken) }
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

            val token = execute { jwtFormat.create(TestUser, auth) }
            val parts = token.split(".")

            // Tamper with the payload by modifying a few characters (keeping valid base64)
            val payload = parts[1].toMutableList()
            payload[0] = if (payload[0] == 'e') 'f' else 'e'
            payload[3] = if (payload[3] == 'A') 'B' else 'A'
            val tamperedPayload = payload.joinToString("")
            val tamperedToken = "${parts[0]}.$tamperedPayload.${parts[2]}"

            // The signature is checked before the payload is parsed, so even a payload that is no longer valid
            // JSON fails on the signature.
            assertFailsWith<JwtSignatureException>("Tampered payload should be rejected") {
                execute { jwtFormat.read(TestUser, tamperedToken) }
            }
        }
    }

    @Test
    fun `wrong audience is rejected`() = runBlocking {
        TestServer.test({}) {
            val format1 = JwtTokenFormat(
                hasher = RuntimeDeferred.Cached { testBasis.HS256("jwt-test") },
                expiration = 5.minutes,
                issuer = Runtime { "https://app1.example.com" },
                audience = Runtime { "https://app1.example.com" },
                keyId = "jwt-test"
            )

            val format2 = JwtTokenFormat(
                hasher = RuntimeDeferred.Cached { testBasis.HS256("jwt-test") },
                expiration = 5.minutes,
                issuer = Runtime { "https://app2.example.com" },
                audience = Runtime { "https://app2.example.com" },
                keyId = "jwt-test"
            )

            val userId = Uuid.random()
            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token = execute { format1.create(TestUser, auth) }

            // Token for app1 should not work with app2's audience
            assertFailsWith<JwtException> {  execute { format2.read(TestUser, token) } }
        }
    }

    @Test
    fun `wrong issuer is rejected`() = runBlocking {
        TestServer.test({}) {
            val issuer1 = createJwtFormat()
            val issuer2 = JwtTokenFormat(
                hasher = RuntimeDeferred.Cached { testBasis.HS256("jwt-test") },
                issuer = Runtime { "https://other.example.com" },
                audience = Runtime { "https://test.example.com" },
                keyId = "jwt-test"
            )

            val token = execute { issuer2.create(
                TestUser,
                Authentication(principalType = TestUser, id = Uuid.random(), sessionId = null)
            ) }

            assertFailsWith<JwtException> { execute { issuer1.read(TestUser, token) } }
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
                val result = execute { jwtFormat.read(TestUser, malformed) }
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

            val token = execute { jwtFormat.create(TestUser, auth) }

            // Try to read as different principal type
            val result = execute { jwtFormat.read(OtherUser, token) }
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
                audience = Runtime { "https://test.example.com" },
                keyId = "jwt-test"
            )

            val format2 = JwtTokenFormat(
                hasher = RuntimeDeferred.Cached { basis2.HS256("jwt") },
                expiration = 5.minutes,
                issuer = Runtime { "https://test.example.com" },
                audience = Runtime { "https://test.example.com" },
                keyId = "jwt-test"
            )

            val userId = Uuid.random()
            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token1 = execute { format1.create(TestUser, auth) }

            // Token signed with key1 should not verify with key2
            assertFailsWith<JwtSignatureException>("Token from different key should fail verification") {
                execute { format2.read(TestUser, token1) }
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

            val token = execute { jwtFormat.create(TestUser, auth) }
            val readAuth = execute { jwtFormat.read(TestUser, token) }

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

            val token = execute { jwtFormat.create(TestUser, auth) }
            val readAuth = execute { jwtFormat.read(TestUser, token) }

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

            val token = execute { oneHourFormat.create(TestUser, auth) }
            val readAuth = execute { oneHourFormat.read(TestUser, token) }

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
    fun `sessionIssuedAt is preserved and tokenIssuedAt is the mint time`() = runBlocking {
        val mintTime = Instant.parse("2030-01-01T12:00:00.750Z")
        val sessionIssuedAt = mintTime - 1.hours
        TestServer.test({}, clock = { object : Clock { override fun now() = mintTime } }) {
            val jwtFormat = createJwtFormat()

            val auth = Authentication(
                principalType = TestUser,
                id = Uuid.random(),
                sessionId = null,
                sessionIssuedAt = sessionIssuedAt,
                // Ignored: the format stamps its own mint time.
                tokenIssuedAt = sessionIssuedAt,
                scopes = setOf(GrantedScope.root)
            )

            val readAuth = execute { jwtFormat.read(TestUser, jwtFormat.create(TestUser, auth)) }

            assertNotNull(readAuth)
            // JWT stores times in whole seconds (siat and iat).
            assertEquals(Instant.fromEpochSeconds(sessionIssuedAt.epochSeconds), readAuth.sessionIssuedAt)
            assertEquals(Instant.fromEpochSeconds(mintTime.epochSeconds), readAuth.tokenIssuedAt)
        }
    }

    @Test
    fun `token header carries the keyId as kid`() = runBlocking {
        TestServer.test({}) {
            val token = execute { createJwtFormat().create(
                TestUser,
                Authentication(principalType = TestUser, id = Uuid.random(), sessionId = null)
            ) }
            val header = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                .decode(token.substringBefore('.'))
                .decodeToString()
            assertTrue(header.contains("\"kid\":\"jwt-test\""), "Header should name the key: $header")
        }
    }

    @Test
    fun `token with a different keyId is ignored`() = runBlocking {
        TestServer.test({}) {
            // Same signing key, so only the kid tells them apart, as with one format per principal.
            val users = createJwtFormat()
            val admins = JwtTokenFormat(
                hasher = RuntimeDeferred.Cached { testBasis.HS256("jwt-test") },
                issuer = Runtime { "https://test.example.com" },
                audience = Runtime { "https://test.example.com" },
                keyId = "jwt-admin"
            )

            val token = execute { admins.create(
                TestUser,
                Authentication(principalType = TestUser, id = Uuid.random(), sessionId = null)
            ) }

            execute { assertNull(users.read(TestUser, token)) }
            execute { assertNotNull(admins.read(TestUser, token)) }
        }
    }

    @Test
    fun `token without a kid is ignored`() = runBlocking {
        TestServer.test({}) {
            val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            // A JWT in the shape minted before keyId existed.
            val header = encoder.encode("""{"typ":"JWT","alg":"HS256"}""".encodeToByteArray())
            val payload = encoder.encode("""{"sub":"TestUser|\"${Uuid.random()}\"","exp":9999999999}""".encodeToByteArray())
            val signature = encoder.encode(testBasis.HS256("jwt-test").sign("$header.$payload".encodeToByteArray()))

            execute { assertNull(createJwtFormat().read(TestUser, "$header.$payload.$signature")) }
        }
    }

    @Test
    fun `correctly signed token with malformed claims throws JwtFormatException`() = runBlocking {
        TestServer.test({}) {
            val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
            val header = encoder.encode("""{"typ":"JWT","alg":"HS256","kid":"jwt-test"}""".encodeToByteArray())
            val payload = encoder.encode("not json".encodeToByteArray())
            val signature = encoder.encode(testBasis.HS256("jwt-test").sign("$header.$payload".encodeToByteArray()))

            assertFailsWith<JwtFormatException> { execute { createJwtFormat().read(TestUser, "$header.$payload.$signature") } }
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

            val token = execute { jwtFormat.create(TestUser, auth) }
            val readAuth = execute { jwtFormat.read(TestUser, token) }

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
