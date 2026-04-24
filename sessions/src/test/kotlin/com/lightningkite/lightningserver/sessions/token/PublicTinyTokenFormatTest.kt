// by Claude
package com.lightningkite.lightningserver.sessions.token

import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.HS256
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Tests for PublicTinyTokenFormat - signed token creation and validation.
 */
class PublicTinyTokenFormatTest {

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

    private fun createPublicTinyTokenFormat(
        basis: SecretBasis = testBasis,
        expiration: kotlin.time.Duration = 5.minutes,
    ): PublicTinyTokenFormat {
        return PublicTinyTokenFormat(
            hasher = RuntimeDeferred.Cached { basis.HS256("public-tiny-token-test") },
            expiration = expiration
        )
    }

    @Test
    fun `create and read valid token`() = runBlocking {
        TestServer.test({}) {
            val format = createPublicTinyTokenFormat()
            val userId = Uuid.random()

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = "test-session-123",
                scopes = setOf(GrantedScope.root)
            )

            val token = format.create(TestUser, auth)
            assertNotNull(token)

            // Token should start with tt/principal name followed by /
            assert(token.startsWith("tt/TestUser/")) { "Token should start with tt/principal name" }

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
            val format = createPublicTinyTokenFormat()
            val userId = Uuid.random()

            val scopes = setOf(
                GrantedScope("api:read"),
                GrantedScope("api:write")
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
            val expiredFormat = PublicTinyTokenFormat(
                hasher = RuntimeDeferred.Cached { testBasis.HS256("public-tiny-token-test") },
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
            val format = createPublicTinyTokenFormat()
            val userId = Uuid.random()

            val auth = Authentication(
                principalType = TestUser,
                id = userId,
                sessionId = null,
                scopes = setOf(GrantedScope.root)
            )

            val token = format.create(TestUser, auth)

            // Token starts with "tt/TestUser/" so reading as OtherUser should return null
            val result = format.read(OtherUser, token)
            assertNull(result, "Token for different principal type should return null")
        }
    }

    @Test
    fun `different signing keys reject tokens`() = runBlocking {
        TestServer.test({}) {
            val basis1 = SecretBasis()
            val basis2 = SecretBasis()

            val format1 = PublicTinyTokenFormat(
                hasher = RuntimeDeferred.Cached { basis1.HS256("public-tiny-token") },
                expiration = 5.minutes
            )

            val format2 = PublicTinyTokenFormat(
                hasher = RuntimeDeferred.Cached { basis2.HS256("public-tiny-token") },
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

            // Token signed with key1 should not verify with key2
            assertFailsWith<TokenException>("Token from different key should fail verification") {
                format2.read(TestUser, token1)
            }
        }
    }

    @Test
    fun `session ID is preserved`() = runBlocking {
        TestServer.test({}) {
            val format = createPublicTinyTokenFormat()
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
}
