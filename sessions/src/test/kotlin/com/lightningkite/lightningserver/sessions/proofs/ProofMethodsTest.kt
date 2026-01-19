// by Claude
package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.RequiredScope
import com.lightningkite.lightningserver.auth.Subscope
import com.lightningkite.lightningserver.auth.register
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.sessions.proofs.extensions.makeProof
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests for ProofMethod interface default implementations and related utilities.
 */
class ProofMethodsTest {

    @Serializable
    data class TestUser(
        override val _id: Uuid = Uuid.random(),
        val email: String = "",
        val phone: String? = null
    ) : HasId<Uuid> {
        companion object : PrincipalType<TestUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<TestUser> = serializer()

            val users = mutableMapOf<Uuid, TestUser>()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): TestUser = users[id] ?: TestUser(id)

            override fun normalizePropertyValue(property: String, value: String): String {
                return if (property == "email") value.lowercase() else value
            }

            context(_: ServerRuntime)
            override fun getProperty(principal: TestUser, property: String): String? {
                return when (property) {
                    "email" -> principal.email.takeIf { it.isNotEmpty() }
                    "phone" -> principal.phone
                    else -> null
                }
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

    /**
     * A simple ProofMethod implementation for testing the interface defaults.
     */
    private class TestProofMethod(
        override val proofSigner: RuntimeDeferred<com.lightningkite.lightningserver.encryption.Signer>,
        override val proofExpiration: Duration,
        via: String = "test",
        property: String? = null,
        strength: Int = 1
    ) : ProofMethod {
        override val info: ProofMethodInfo = ProofMethodInfo(
            via = via,
            property = property,
            strength = strength
        )
    }

    @Test
    fun `verify returns true for valid proof`() = runBlocking {
        TestUser.users.clear()

        object : ServerBuilder() {
            init {
                register(TestUser)
            }
        }.let { server ->
            server.test({}) {
                val signer = testBasis.signer("proof")
                val proofMethod = TestProofMethod(
                    proofSigner = RuntimeDeferred.Cached { signer },
                    proofExpiration = 1.hours,
                    via = "test"
                )

                // Create a valid proof using the signer
                val proof = signer.makeProof(
                    info = proofMethod.info,
                    property = "email",
                    value = "test@example.com",
                    at = kotlin.time.Clock.System.now(),
                    expireAfter = 1.hours
                )

                // Verify should return true
                assertTrue(proofMethod.verify(proof))
            }
        }
    }

    @Test
    fun `verify returns false when via does not match`() = runBlocking {
        TestUser.users.clear()

        object : ServerBuilder() {
            init {
                register(TestUser)
            }
        }.let { server ->
            server.test({}) {
                val signer = testBasis.signer("proof")
                val proofMethod = TestProofMethod(
                    proofSigner = RuntimeDeferred.Cached { signer },
                    proofExpiration = 1.hours,
                    via = "test"
                )

                // Create a proof with different via
                val proof = signer.makeProof(
                    info = ProofMethodInfo(via = "other", property = null, strength = 1),
                    property = "email",
                    value = "test@example.com",
                    at = kotlin.time.Clock.System.now(),
                    expireAfter = 1.hours
                )

                // Verify should return false because via doesn't match
                assertFalse(proofMethod.verify(proof))
            }
        }
    }

    @Test
    fun `verify returns false when property does not match`() = runBlocking {
        TestUser.users.clear()

        object : ServerBuilder() {
            init {
                register(TestUser)
            }
        }.let { server ->
            server.test({}) {
                val signer = testBasis.signer("proof")
                // Create a method that requires a specific property
                val proofMethod = TestProofMethod(
                    proofSigner = RuntimeDeferred.Cached { signer },
                    proofExpiration = 1.hours,
                    via = "test",
                    property = "email"  // Requires email
                )

                // Create a proof with different property
                val proof = signer.makeProof(
                    info = proofMethod.info.copy(property = "phone"),
                    property = "phone",
                    value = "+1234567890",
                    at = kotlin.time.Clock.System.now(),
                    expireAfter = 1.hours
                )

                // Verify should return false because property doesn't match
                assertFalse(proofMethod.verify(proof))
            }
        }
    }

    @Test
    fun `verify returns true when method has no required property`() = runBlocking {
        TestUser.users.clear()

        object : ServerBuilder() {
            init {
                register(TestUser)
            }
        }.let { server ->
            server.test({}) {
                val signer = testBasis.signer("proof")
                // Create a method that accepts any property
                val proofMethod = TestProofMethod(
                    proofSigner = RuntimeDeferred.Cached { signer },
                    proofExpiration = 1.hours,
                    via = "test",
                    property = null  // Any property is fine
                )

                // Create a proof with any property
                val proof = signer.makeProof(
                    info = proofMethod.info,
                    property = "phone",
                    value = "+1234567890",
                    at = kotlin.time.Clock.System.now(),
                    expireAfter = 1.hours
                )

                // Verify should return true because method accepts any property
                assertTrue(proofMethod.verify(proof))
            }
        }
    }

    @Test
    fun `verify returns false when proof is expired`() = runBlocking {
        TestUser.users.clear()

        object : ServerBuilder() {
            init {
                register(TestUser)
            }
        }.let { server ->
            server.test({}) {
                val signer = testBasis.signer("proof")
                val proofMethod = TestProofMethod(
                    proofSigner = RuntimeDeferred.Cached { signer },
                    proofExpiration = 1.hours,
                    via = "test"
                )

                // Create an expired proof (created in the past with short expiry)
                val pastTime = Instant.fromEpochMilliseconds(0)
                val proof = signer.makeProof(
                    info = proofMethod.info,
                    property = "email",
                    value = "test@example.com",
                    at = pastTime,
                    expireAfter = 1.minutes
                )

                // Verify should return false because proof is expired
                assertFalse(proofMethod.verify(proof))
            }
        }
    }

    @Test
    fun `verify returns false when signature is invalid`() = runBlocking {
        TestUser.users.clear()

        object : ServerBuilder() {
            init {
                register(TestUser)
            }
        }.let { server ->
            server.test({}) {
                val signer = testBasis.signer("proof")
                val differentSigner = SecretBasis().signer("different")

                val proofMethod = TestProofMethod(
                    proofSigner = RuntimeDeferred.Cached { signer },
                    proofExpiration = 1.hours,
                    via = "test"
                )

                // Create a proof signed with a different signer
                val proof = differentSigner.makeProof(
                    info = proofMethod.info,
                    property = "email",
                    value = "test@example.com",
                    at = kotlin.time.Clock.System.now(),
                    expireAfter = 1.hours
                )

                // Verify should return false because signature doesn't match
                assertFalse(proofMethod.verify(proof))
            }
        }
    }

    @Test
    fun `established returns true when property exists on subject`() = runBlocking {
        TestUser.users.clear()
        val userId = Uuid.random()
        val user = TestUser(userId, email = "test@example.com", phone = "+1234567890")
        TestUser.users[userId] = user

        object : ServerBuilder() {
            init {
                register(TestUser)
            }
        }.let { server ->
            server.test({}) {
                val signer = testBasis.signer("proof")
                // Method that checks for email property
                val proofMethod = TestProofMethod(
                    proofSigner = RuntimeDeferred.Cached { signer },
                    proofExpiration = 1.hours,
                    via = "test",
                    property = "email"
                )

                // User has email, so established should return true
                assertTrue(proofMethod.established(TestUser, user))
            }
        }
    }

    @Test
    fun `established returns false when property is missing on subject`() = runBlocking {
        TestUser.users.clear()
        val userId = Uuid.random()
        val user = TestUser(userId, email = "test@example.com", phone = null)
        TestUser.users[userId] = user

        object : ServerBuilder() {
            init {
                register(TestUser)
            }
        }.let { server ->
            server.test({}) {
                val signer = testBasis.signer("proof")
                // Method that checks for phone property
                val proofMethod = TestProofMethod(
                    proofSigner = RuntimeDeferred.Cached { signer },
                    proofExpiration = 1.hours,
                    via = "test",
                    property = "phone"
                )

                // User doesn't have phone, so established should return false
                assertFalse(proofMethod.established(TestUser, user))
            }
        }
    }

    @Test
    fun `established returns false when method has no required property`() = runBlocking {
        TestUser.users.clear()
        val userId = Uuid.random()
        val user = TestUser(userId, email = "test@example.com")
        TestUser.users[userId] = user

        object : ServerBuilder() {
            init {
                register(TestUser)
            }
        }.let { server ->
            server.test({}) {
                val signer = testBasis.signer("proof")
                // Method with no required property (like password auth)
                val proofMethod = TestProofMethod(
                    proofSigner = RuntimeDeferred.Cached { signer },
                    proofExpiration = 1.hours,
                    via = "test",
                    property = null
                )

                // Default implementation returns false when property is null
                assertFalse(proofMethod.established(TestUser, user))
            }
        }
    }

    @Test
    fun `proofMethodAuth creates correct authentication requirement`() = runBlocking {
        TestUser.users.clear()

        object : ServerBuilder() {
            init {
                register(TestUser)
            }
        }.let { server ->
            server.test({}) {
                val signer = testBasis.signer("proof")
                val proofMethod = TestProofMethod(
                    proofSigner = RuntimeDeferred.Cached { signer },
                    proofExpiration = 1.hours,
                    via = "custom-auth"
                )

                val auth = proofMethod.proofMethodAuth

                // Should require authentication
                assertNotNull(auth)

                // Should have correct scope based on via
                val expectedScope = ProofMethod.baseScope.subscope(Subscope("custom-auth"))
                assertTrue(auth.scopes.contains(expectedScope))

                // Should have 10 minute max age
                assertEquals(10.minutes, auth.maxAge)
            }
        }
    }

    @Test
    fun `baseScope is auth colon proofs`() {
        assertEquals("auth:proofs", ProofMethod.baseScope.asString)
    }

    @Test
    fun `ProofMethodInfo holds correct values`() {
        val info = ProofMethodInfo(
            via = "password",
            property = "email",
            strength = 10
        )

        assertEquals("password", info.via)
        assertEquals("email", info.property)
        assertEquals(10, info.strength)
    }

    @Test
    fun `ProofMethodInfo property can be null`() {
        val info = ProofMethodInfo(
            via = "password",
            property = null,
            strength = 5
        )

        assertEquals("password", info.via)
        assertEquals(null, info.property)
        assertEquals(5, info.strength)
    }
}
