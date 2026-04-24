// by Claude
package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.register
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Tests for PasswordProofEndpoints - password-based authentication.
 */
class PasswordProofEndpointsTest {

    @Serializable
    data class TestUser(
        override val _id: Uuid = Uuid.random(),
        val email: String = "",
    ) : HasId<Uuid> {
        companion object : PrincipalType<TestUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<TestUser> = serializer()

            // Store for test users
            val users = mutableMapOf<Uuid, TestUser>()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): TestUser = users[id] ?: TestUser(id)

            override fun normalizePropertyValue(property: String, value: String): String {
                return if (property == "email") value.lowercase() else value
            }

            context(server: ServerRuntime)
            override suspend fun fetchByProperty(property: String, value: String): TestUser? {
                return when (property) {
                    "email" -> users.values.find { it.email == value }
                    else -> super.fetchByProperty(property, value)
                }
            }
        }
    }

    private val testBasis = SecretBasis()

    @Test
    fun `establish creates password secret`() = runBlocking {
        TestUser.users.clear()
        val userId = Uuid.random()
        val user = TestUser(userId, "test@example.com")
        TestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            init {
                register(TestUser)
            }

            val passwordProof = path.path("auth").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                // Use the direct establish function instead of the endpoint
                server.passwordProof.establish(TestUser, userId, EstablishPassword("mySecurePassword123"))

                // Verify password secret was created
                val secrets = server.passwordProof.modelInfo.table()
                    .find(condition<PasswordSecret> { it.subjectId eq userId.toString() })
                    .firstOrNull()

                assertNotNull(secrets)
                assertEquals(userId.toString(), secrets.subjectId)
                // Password should be hashed, not stored as plaintext
                assertNotEquals("mySecurePassword123", secrets.hash)
            }
        }
    }

    @Test
    fun `establish rejects hint containing password`() = runBlocking {
        TestUser.users.clear()
        val userId = Uuid.random()
        val user = TestUser(userId, "test@example.com")
        TestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            init {
                register(TestUser)
            }

            val passwordProof = path.path("auth").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                // Try to establish with hint containing the password
                assertFailsWith<BadRequestException>("Hint containing password should be rejected") {
                    server.passwordProof.establish(
                        TestUser, userId, EstablishPassword(
                            password = "secretPassword",
                            hint = "My hint is secretPassword"
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `prove returns valid proof with correct password`() = runBlocking {
        TestUser.users.clear()
        val userId = Uuid.random()
        val user = TestUser(userId, "test@example.com")
        TestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            init {
                register(TestUser)
            }

            val passwordProof = path.path("auth").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                // First establish password
                server.passwordProof.establish(TestUser, userId, EstablishPassword("correctPassword"))

                // Now prove with correct password
                val proof = server.passwordProof.prove.test(
                    null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "email",
                        value = "test@example.com",
                        password = "correctPassword"
                    )
                )

                assertNotNull(proof)
                assertEquals("email", proof.property)
                assertEquals("test@example.com", proof.value)
                assertNotNull(proof.signature)
            }
        }
    }

    @Test
    fun `prove rejects incorrect password`() = runBlocking {
        TestUser.users.clear()
        val userId = Uuid.random()
        val user = TestUser(userId, "test@example.com")
        TestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            init {
                register(TestUser)
            }

            val passwordProof = path.path("auth").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                // First establish password
                server.passwordProof.establish(TestUser, userId, EstablishPassword("correctPassword"))

                // Try to prove with wrong password
                assertFailsWith<BadRequestException>("Wrong password should be rejected") {
                    server.passwordProof.prove.test(
                        null, IdentificationAndPassword(
                            type = "TestUser",
                            property = "email",
                            value = "test@example.com",
                            password = "wrongPassword"
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `password is stored as hash not plaintext`() = runBlocking {
        TestUser.users.clear()
        val userId = Uuid.random()
        val user = TestUser(userId, "test@example.com")
        TestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            init {
                register(TestUser)
            }

            val passwordProof = path.path("auth").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val password = "myPlaintextPassword"
                server.passwordProof.establish(TestUser, userId, EstablishPassword(password))

                // Get the stored secret
                val secret = server.passwordProof.modelInfo.table()
                    .find(condition<PasswordSecret> { it.subjectId eq userId.toString() })
                    .firstOrNull()

                assertNotNull(secret)
                // Hash should not equal plaintext
                assertNotEquals(password, secret.hash)
                // But password should check against the hash
                assertTrue(password.checkAgainstHash(secret.hash))
            }
        }
    }

    @Test
    fun `new password disables old password`() = runBlocking {
        TestUser.users.clear()
        val userId = Uuid.random()
        val user = TestUser(userId, "test@example.com")
        TestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            init {
                register(TestUser)
            }

            val passwordProof = path.path("auth").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                // Establish first password
                server.passwordProof.establish(TestUser, userId, EstablishPassword("oldPassword"))

                // Establish new password
                server.passwordProof.establish(TestUser, userId, EstablishPassword("newPassword"))

                // Old password should no longer work
                assertFailsWith<BadRequestException>("Old password should be disabled") {
                    server.passwordProof.prove.test(
                        null, IdentificationAndPassword(
                            type = "TestUser",
                            property = "email",
                            value = "test@example.com",
                            password = "oldPassword"
                        )
                    )
                }

                // New password should work
                val proof = server.passwordProof.prove.test(
                    null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "email",
                        value = "test@example.com",
                        password = "newPassword"
                    )
                )
                assertNotNull(proof)
            }
        }
    }

    @Test
    fun `prove normalizes email to lowercase`() = runBlocking {
        TestUser.users.clear()
        val userId = Uuid.random()
        val user = TestUser(userId, "test@example.com")
        TestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            init {
                register(TestUser)
            }

            val passwordProof = path.path("auth").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                server.passwordProof.establish(TestUser, userId, EstablishPassword("password123"))

                // Prove with uppercase email
                val proof = server.passwordProof.prove.test(
                    null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "email",
                        value = "TEST@EXAMPLE.COM",
                        password = "password123"
                    )
                )

                assertNotNull(proof)
                // Proof value should be normalized to lowercase
                assertEquals("test@example.com", proof.value)
            }
        }
    }

    @Test
    fun `established returns true when password exists`() = runBlocking {
        TestUser.users.clear()
        val userId = Uuid.random()
        val user = TestUser(userId, "test@example.com")
        TestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            init {
                register(TestUser)
            }

            val passwordProof = path.path("auth").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                // Before establishing, should return false
                assertFalse(server.passwordProof.established(TestUser, user))

                // Establish password
                server.passwordProof.establish(TestUser, userId, EstablishPassword("password"))

                // After establishing, should return true
                assertTrue(server.passwordProof.established(TestUser, user))
            }
        }
    }

    @Test
    fun `custom password evaluation is called`() = runBlocking {
        TestUser.users.clear()
        val userId = Uuid.random()
        val user = TestUser(userId, "test@example.com")
        TestUser.users[userId] = user

        var evaluationCalled = false

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            init {
                register(TestUser)
            }

            val passwordProof = path.path("auth").path("password") include PasswordProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours,
                evaluatePassword = { password ->
                    evaluationCalled = true
                    if (password.length < 8) throw BadRequestException("Password too short")
                }
            )
        }.let { server ->
            server.test({}) {
                // Short password should be rejected
                assertFailsWith<BadRequestException>("Short password should be rejected") {
                    server.passwordProof.establish(TestUser, userId, EstablishPassword("short"))
                }
                assertTrue(evaluationCalled)

                // Long enough password should work
                evaluationCalled = false
                server.passwordProof.establish(TestUser, userId, EstablishPassword("longEnoughPassword"))
                assertTrue(evaluationCalled)
            }
        }
    }
}
