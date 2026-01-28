// by Claude
package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.auth.Authentication
import com.lightningkite.lightningserver.auth.GrantedScope
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.id
import com.lightningkite.lightningserver.auth.register
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.cipher
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Tests for PrivateTinyTokenFormat - encrypted token creation and validation.
 */
class PrivateTinyTokenFormatTest {

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

    private fun createTinyTokenFormat(
        basis: SecretBasis = testBasis,
        expiration: kotlin.time.Duration = 5.minutes
    ): PrivateTinyTokenFormat {
        return PrivateTinyTokenFormat(
            cipher = RuntimeDeferred.Cached { basis.cipher("tiny-token-test") },
            expiration = expiration
        )
    }

    @Test
    fun `create and read valid token`() = runBlocking {
        TestServer.test({}) {
            val format = createTinyTokenFormat()
            val userId = Uuid.random()

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = "test-session-123",
                scopes = setOf(GrantedScope.root)
            )

            val token = format.create(TestUser, auth)
            assertNotNull(token)

            // Token should start with principal name followed by /
            assert(token.startsWith("TestUser/")) { "Token should start with principal name" }

            // Read back the token
            val readAuth = format.read(TestUser, token)
            assertNotNull(readAuth, "Should be able to read back the token")
            assertEquals(userId, readAuth.id)
            assertEquals("test-session-123", readAuth.sessionId)
        }
    }

    @Test
    fun `token preserves scopes`() = runBlocking {
        TestServer.test({}) {
            val format = createTinyTokenFormat()
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

            val token = format.create(TestUser, auth)
            val readAuth = format.read(TestUser, token)

            assertNotNull(readAuth)
            assertEquals(scopes, readAuth.scopes)
        }
    }

    @Test
    fun `expired token throws TokenException`() = runBlocking {
        TestServer.test({}) {
            // Create format with already-expired tokens
            val expiredFormat = PrivateTinyTokenFormat(
                cipher = RuntimeDeferred.Cached { testBasis.cipher("tiny-token-test") },
                expiration = (-1).seconds // Already expired
            )

            val userId = Uuid.random()
            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token = expiredFormat.create(TestUser, auth)

            assertFailsWith<TokenException>("Expired token should throw") {
                expiredFormat.read(TestUser, token)
            }
        }
    }

    @Test
    fun `wrong principal type returns null`() = runBlocking {
        TestServer.test({}) {
            val format = createTinyTokenFormat()
            val userId = Uuid.random()

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token = format.create(TestUser, auth)

            // Token starts with "TestUser/" so reading as OtherUser should return null
            val result = format.read(OtherUser, token)
            assertNull(result, "Token for different principal type should return null")
        }
    }

    @Test
    fun `different encryption keys produce incompatible tokens`() = runBlocking {
        TestServer.test({}) {
            val basis1 = SecretBasis()
            val basis2 = SecretBasis()

            val format1 = PrivateTinyTokenFormat(
                cipher = RuntimeDeferred.Cached { basis1.cipher("tiny-token") },
                expiration = 5.minutes
            )

            val format2 = PrivateTinyTokenFormat(
                cipher = RuntimeDeferred.Cached { basis2.cipher("tiny-token") },
                expiration = 5.minutes
            )

            val userId = Uuid.random()
            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token1 = format1.create(TestUser, auth)

            // Token encrypted with key1 should not decrypt with key2
            assertFailsWith<TokenException>("Token from different key should fail decryption") {
                format2.read(TestUser, token1)
            }
        }
    }

    @Test
    fun `tampered token throws TokenException`() = runBlocking {
        TestServer.test({}) {
            val format = createTinyTokenFormat()
            val userId = Uuid.random()

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token = format.create(TestUser, auth)

            // Tamper with the encrypted part (after the /)
            val prefix = token.substringBefore('/') + "/"
            val encrypted = token.substringAfter('/')
            val tamperedEncrypted = encrypted.toMutableList()
            if (tamperedEncrypted.isNotEmpty()) {
                tamperedEncrypted[0] = if (tamperedEncrypted[0] == 'A') 'B' else 'A'
                if (tamperedEncrypted.size > 5) {
                    tamperedEncrypted[5] = if (tamperedEncrypted[5] == 'A') 'B' else 'A'
                }
            }
            val tamperedToken = prefix + tamperedEncrypted.joinToString("")

            assertFailsWith<TokenException>("Tampered token should throw") {
                format.read(TestUser, tamperedToken)
            }
        }
    }

    @Test
    fun `session ID is preserved`() = runBlocking {
        TestServer.test({}) {
            val format = createTinyTokenFormat()
            val userId = Uuid.random()
            val sessionId = "unique-session-${Uuid.random()}"

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = sessionId,
                scopes = setOf(GrantedScope.root)
            )

            val token = format.create(TestUser, auth)
            val readAuth = format.read(TestUser, token)

            assertNotNull(readAuth)
            assertEquals(sessionId, readAuth.sessionId)
        }
    }

    @Test
    fun `null session ID is handled`() = runBlocking {
        TestServer.test({}) {
            val format = createTinyTokenFormat()
            val userId = Uuid.random()

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token = format.create(TestUser, auth)
            val readAuth = format.read(TestUser, token)

            assertNotNull(readAuth)
            assertNull(readAuth.sessionId)
        }
    }

    @Test
    fun `malformed token returns null or throws`() = runBlocking {
        TestServer.test({}) {
            val format = createTinyTokenFormat()

            // Various malformed tokens that don't match the principal name prefix
            val malformedTokens = listOf(
                "not-a-token",
                "",
                "WrongPrincipal/abc123",
            )

            for (malformed in malformedTokens) {
                val result = format.read(TestUser, malformed)
                assertNull(result, "Malformed token '$malformed' should return null")
            }
        }
    }

    @Test
    fun `token includes expiration based on format configuration`() = runBlocking {
        TestServer.test({}) {
            val oneMinuteFormat = createTinyTokenFormat(expiration = 1.minutes)
            val userId = Uuid.random()

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token = oneMinuteFormat.create(TestUser, auth)
            val readAuth = oneMinuteFormat.read(TestUser, token)

            assertNotNull(readAuth)
            // The PrivateTinyTokenFormat sets expiration on the auth copy
            assertNotNull(readAuth.expiration)
        }
    }

    @Test
    fun `different cipher variants produce different tokens`() = runBlocking {
        TestServer.test({}) {
            val format1 = PrivateTinyTokenFormat(
                cipher = RuntimeDeferred.Cached { testBasis.cipher("variant1") },
                expiration = 5.minutes
            )

            val format2 = PrivateTinyTokenFormat(
                cipher = RuntimeDeferred.Cached { testBasis.cipher("variant2") },
                expiration = 5.minutes
            )

            val userId = Uuid.random()
            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token1 = format1.create(TestUser, auth)
            val token2 = format2.create(TestUser, auth)

            // Same auth, different cipher variants should produce different tokens
            assert(token1 != token2) { "Different cipher variants should produce different tokens" }

            // And they shouldn't be readable by each other
            assertFailsWith<TokenException>("Token from variant1 should not decrypt with variant2") {
                format2.read(TestUser, token1)
            }
        }
    }
}
