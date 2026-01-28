// by Claude
package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.GrantedScope
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.id
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.sessions.proofs.IdentificationAndPassword
import com.lightningkite.lightningserver.sessions.proofs.PasswordProofEndpoints
import com.lightningkite.lightningserver.sessions.proofs.Proof
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Integration tests for AuthEndpoints - full authentication flow testing.
 */
class AuthEndpointsIntegrationTest {

    @Serializable
    data class AuthTestUser(
        override val _id: Uuid = Uuid.random(),
        val email: String = "",
        val phone: String = "",
        val isAdmin: Boolean = false
    ) : HasId<Uuid> {
        companion object : PrincipalType<AuthTestUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<AuthTestUser> = serializer()

            val users = mutableMapOf<Uuid, AuthTestUser>()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): AuthTestUser = users[id] ?: AuthTestUser(id)

            override fun normalizePropertyValue(property: String, value: String): String {
                return if (property == "email") value.lowercase() else value
            }

            context(server: ServerRuntime)
            override suspend fun fetchByProperty(property: String, value: String): AuthTestUser? {
                return when (property) {
                    "email" -> users.values.find { it.email == value }
                    "phone" -> users.values.find { it.phone == value }
                    "AuthTestUser/_id" -> users.values.find { it._id.toString() == value }
                    else -> super.fetchByProperty(property, value)
                }
            }

        }
    }

    private val testBasis = SecretBasis()

    class TestAuthEndpoints(
        database: Runtime<Database>,
        private val proofStrength: Int = 100
    ) : AuthEndpoints<AuthTestUser, Uuid>(
        principal = AuthTestUser,
        database = database,
        tokenFormat = Runtime { PrivateTinyTokenFormat() }
    ) {
        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: AuthTestUser): Instant? =
            com.lightningkite.lightningserver.runtime.now() + 30.days

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: AuthTestUser): Duration? = 7.days

        context(server: ServerRuntime)
        override suspend fun requiredProofStrengthFor(subject: AuthTestUser): Int =
            if (subject.isAdmin) proofStrength * 2 else proofStrength
    }

    @Test
    fun `login with valid password proof creates session`() = runBlocking {
        AuthTestUser.users.clear()
        val userId = Uuid.random()
        val user = AuthTestUser(userId, "test@example.com", "555-1234")
        AuthTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            val passwordEndpoints = path.path("proof").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )

            val authEndpoints = path.path("auth") include TestAuthEndpoints(database = database)
        }.let { server ->
            server.test({}) {
                // Establish password
                server.passwordEndpoints.establish(AuthTestUser, userId, EstablishPassword("securePassword123"))

                // Get password proof
                val proof = server.passwordEndpoints.prove.test(null, IdentificationAndPassword(
                    type = "AuthTestUser",
                    property = "email",
                    value = "test@example.com",
                    password = "securePassword123"
                ))

                // Login with the proof
                val result = server.authEndpoints.login.test(null, listOf(proof))

                assertNotNull(result)
                assertEquals(userId, result.id)
                assertNotNull(result.refreshToken, "Login should return refresh token when proof is sufficient")
            }
        }
    }

    @Test
    fun `admin login with sufficient proof creates session`() = runBlocking {
        AuthTestUser.users.clear()
        val userId = Uuid.random()
        // Admin user requires 2x proof strength, but system caps at max achievable
        val user = AuthTestUser(userId, "admin@example.com", "555-1234", isAdmin = true)
        AuthTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            val passwordEndpoints = path.path("proof").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )

            val authEndpoints = path.path("auth") include TestAuthEndpoints(database = database)
        }.let { server ->
            server.test({}) {
                // Establish password
                server.passwordEndpoints.establish(AuthTestUser, userId, EstablishPassword("adminPassword"))

                // Get password proof
                val proof = server.passwordEndpoints.prove.test(null, IdentificationAndPassword(
                    type = "AuthTestUser",
                    property = "email",
                    value = "admin@example.com",
                    password = "adminPassword"
                ))

                // Login with the proof - system caps required strength at max achievable
                val result = server.authEndpoints.login.test(null, listOf(proof))

                assertNotNull(result)
                assertEquals(userId, result.id)
                // Admin can log in because required strength is capped at what's achievable
                assertNotNull(result.refreshToken, "Admin should be able to login when strength is capped at max achievable")
            }
        }
    }

    @Test
    fun `proofsCheck validates proofs without creating session`() = runBlocking {
        AuthTestUser.users.clear()
        val userId = Uuid.random()
        val user = AuthTestUser(userId, "test@example.com", "555-1234")
        AuthTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            val passwordEndpoints = path.path("proof").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )

            val authEndpoints = path.path("auth") include TestAuthEndpoints(database = database)
        }.let { server ->
            server.test({}) {
                // Establish password
                server.passwordEndpoints.establish(AuthTestUser, userId, EstablishPassword("myPassword"))

                // Get password proof
                val proof = server.passwordEndpoints.prove.test(null, IdentificationAndPassword(
                    type = "AuthTestUser",
                    property = "email",
                    value = "test@example.com",
                    password = "myPassword"
                ))

                // Check proofs
                val result = server.authEndpoints.proofsCheck.test(null, listOf(proof))

                assertNotNull(result)
                assertEquals(userId, result.id)
                assertTrue(result.readyToLogIn, "User with sufficient proof should be ready to login")
                assertNotNull(result.maxExpiration)
            }
        }
    }

    @Test
    fun `login with mismatched user proofs fails`() = runBlocking {
        AuthTestUser.users.clear()
        val userId1 = Uuid.random()
        val user1 = AuthTestUser(userId1, "user1@example.com", "555-1111")
        AuthTestUser.users[userId1] = user1

        val userId2 = Uuid.random()
        val user2 = AuthTestUser(userId2, "user2@example.com", "555-2222")
        AuthTestUser.users[userId2] = user2

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            val passwordEndpoints = path.path("proof").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )

            val authEndpoints = path.path("auth") include TestAuthEndpoints(database = database)
        }.let { server ->
            server.test({}) {
                // Establish passwords for both users
                server.passwordEndpoints.establish(AuthTestUser, userId1, EstablishPassword("password1"))
                server.passwordEndpoints.establish(AuthTestUser, userId2, EstablishPassword("password2"))

                // Get proofs for both users
                val proof1 = server.passwordEndpoints.prove.test(null, IdentificationAndPassword(
                    type = "AuthTestUser",
                    property = "email",
                    value = "user1@example.com",
                    password = "password1"
                ))

                val proof2 = server.passwordEndpoints.prove.test(null, IdentificationAndPassword(
                    type = "AuthTestUser",
                    property = "email",
                    value = "user2@example.com",
                    password = "password2"
                ))

                // Try to login with proofs from different users - should fail
                assertFailsWith<Exception>("Login with proofs from different users should fail") {
                    server.authEndpoints.login.test(null, listOf(proof1, proof2))
                }
            }
        }
    }

    @Test
    fun `login2 allows custom session parameters`() = runBlocking {
        AuthTestUser.users.clear()
        val userId = Uuid.random()
        val user = AuthTestUser(userId, "test@example.com", "555-1234")
        AuthTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            val passwordEndpoints = path.path("proof").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )

            val authEndpoints = path.path("auth") include TestAuthEndpoints(database = database)
        }.let { server ->
            server.test({}) {
                // Establish password
                server.passwordEndpoints.establish(AuthTestUser, userId, EstablishPassword("securePassword"))

                // Get password proof
                val proof = server.passwordEndpoints.prove.test(null, IdentificationAndPassword(
                    type = "AuthTestUser",
                    property = "email",
                    value = "test@example.com",
                    password = "securePassword"
                ))

                // Login2 with custom parameters
                val customScopes = setOf(GrantedScope("api:read"))
                val result = server.authEndpoints.login2.test(null, LogInRequest(
                    proofs = listOf(proof),
                    label = "Test Device",
                    scopes = customScopes
                ))

                assertNotNull(result)
                assertEquals(userId, result.id)
                assertNotNull(result.refreshToken, "Should create session with custom parameters")
            }
        }
    }

    @Test
    fun `empty proofs list fails with appropriate error`() = runBlocking {
        AuthTestUser.users.clear()

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            val authEndpoints = path.path("auth") include TestAuthEndpoints(database = database)
        }.let { server ->
            server.test({}) {
                // Login with empty proofs should fail
                assertFailsWith<Exception>("Empty proofs list should fail") {
                    server.authEndpoints.login.test(null, emptyList())
                }
            }
        }
    }

    @Test
    fun `login with nonexistent user fails`() = runBlocking {
        AuthTestUser.users.clear()

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            val passwordEndpoints = path.path("proof").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )

            val authEndpoints = path.path("auth") include TestAuthEndpoints(database = database)
        }.let { server ->
            server.test({}) {
                // Try to prove with nonexistent user
                assertFailsWith<Exception>("Nonexistent user should fail") {
                    server.passwordEndpoints.prove.test(null, IdentificationAndPassword(
                        type = "AuthTestUser",
                        property = "email",
                        value = "nonexistent@example.com",
                        password = "anyPassword"
                    ))
                }
            }
        }
    }

    @Test
    fun `proofsCheck returns correct authentication status`() = runBlocking {
        AuthTestUser.users.clear()
        val userId = Uuid.random()
        val user = AuthTestUser(userId, "test@example.com", "555-1234", isAdmin = true)
        AuthTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            val passwordEndpoints = path.path("proof").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )

            val authEndpoints = path.path("auth") include TestAuthEndpoints(database = database)
        }.let { server ->
            server.test({}) {
                // Establish password
                server.passwordEndpoints.establish(AuthTestUser, userId, EstablishPassword("adminPassword"))

                // Get password proof
                val proof = server.passwordEndpoints.prove.test(null, IdentificationAndPassword(
                    type = "AuthTestUser",
                    property = "email",
                    value = "test@example.com",
                    password = "adminPassword"
                ))

                // Check proofs for admin user
                val result = server.authEndpoints.proofsCheck.test(null, listOf(proof))

                assertNotNull(result)
                assertEquals(userId, result.id)
                // Required strength is capped at max achievable, so user IS ready
                assertTrue(result.readyToLogIn, "User should be ready when proof meets capped required strength")
                // The strength required should be capped at what's achievable (password strength = 10)
                assertTrue(result.strengthRequired > 0, "Should have positive strength requirement")
            }
        }
    }

    @Test
    fun `refresh token from login can be used for token exchange`() = runBlocking {
        AuthTestUser.users.clear()
        val userId = Uuid.random()
        val user = AuthTestUser(userId, "test@example.com", "555-1234")
        AuthTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            val passwordEndpoints = path.path("proof").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )

            val authEndpoints = path.path("auth") include TestAuthEndpoints(database = database)
        }.let { server ->
            server.test({}) {
                // Establish password
                server.passwordEndpoints.establish(AuthTestUser, userId, EstablishPassword("testPassword"))

                // Get password proof
                val proof = server.passwordEndpoints.prove.test(null, IdentificationAndPassword(
                    type = "AuthTestUser",
                    property = "email",
                    value = "test@example.com",
                    password = "testPassword"
                ))

                // Login and get refresh token
                val loginResult = server.authEndpoints.login.test(null, listOf(proof))
                assertNotNull(loginResult.refreshToken)

                // Use refresh token to get access token
                val accessToken = server.authEndpoints.tokenSimple.test(null, loginResult.refreshToken!!)

                assertNotNull(accessToken)
                assertTrue(accessToken.isNotEmpty())
            }
        }
    }
}
