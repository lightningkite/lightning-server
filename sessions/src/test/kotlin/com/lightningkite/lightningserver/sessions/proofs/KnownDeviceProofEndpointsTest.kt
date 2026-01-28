// by Claude
package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.register
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.sessions.KnownDeviceSecret
import com.lightningkite.lightningserver.sessions.proofs.extensions.verify
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Tests for KnownDeviceProofEndpoints - device recognition authentication.
 */
class KnownDeviceProofEndpointsTest {

    @Serializable
    data class TestUser(
        override val _id: Uuid = Uuid.random(),
        val email: String = ""
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
    fun `establish creates known device secret`() = runBlocking {
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

            val knownDevice = path.path("auth").path("known-device") include KnownDeviceProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours,
                expires = Runtime.Constant(30.days)
            )
        }.let { server ->
            server.test({}) {
                // Establish a known device
                val result = server.knownDevice.establish(TestUser, userId, "Chrome on Mac / 127.0.0.1")

                // Should return a secret in format "uuid/uuid"
                assertNotNull(result.secret)
                assertTrue(result.secret.contains("/"))

                // Check that the secret was stored
                val secrets = server.knownDevice.modelInfo.table().find(Condition.Always).toList()
                assertEquals(1, secrets.size)

                val secret = secrets.first()
                assertEquals(TestUser.name, secret.subjectType)
                assertEquals(userId.toString(), secret.subjectId)
                assertEquals("Chrome on Mac / 127.0.0.1", secret.deviceInfo)
                assertNotNull(secret.establishedAt)
                assertNotNull(secret.expiresAt)
            }
        }
    }

    @Test
    fun `prove returns valid proof with correct secret`() = runBlocking {
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

            val knownDevice = path.path("auth").path("known-device") include KnownDeviceProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                // First establish a known device
                val established = server.knownDevice.establish(TestUser, userId, "Test Device")

                // Now prove with the secret
                val proof = server.knownDevice.prove.test(null, established.secret)

                assertNotNull(proof)
                assertEquals("known-device", proof.via)
                assertEquals("TestUser/_id", proof.property)
                assertEquals(userId.toString(), proof.value)
                assertNotNull(proof.signature)
                assertEquals(3, proof.strength)

                // Verify the proof signature
                val signer = testBasis.signer("proof")
                assertTrue(signer.verify(proof))
            }
        }
    }

    @Test
    fun `prove rejects incorrect secret`() = runBlocking {
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

            val knownDevice = path.path("auth").path("known-device") include KnownDeviceProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                // Establish a known device
                val established = server.knownDevice.establish(TestUser, userId, "Test Device")

                // Try to prove with wrong secret (same id, wrong secret)
                val secretId = established.secret.substringBefore('/')
                val wrongSecret = "$secretId/${Uuid.random()}"

                assertFailsWith<BadRequestException>("Wrong secret should be rejected") {
                    server.knownDevice.prove.test(null, wrongSecret)
                }
            }
        }
    }

    @Test
    fun `prove rejects non-existent device id`() = runBlocking {
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

            val knownDevice = path.path("auth").path("known-device") include KnownDeviceProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                // Try to prove with non-existent device
                val nonExistentSecret = "${Uuid.random()}/${Uuid.random()}"

                assertFailsWith<BadRequestException>("Non-existent device should be rejected") {
                    server.knownDevice.prove.test(null, nonExistentSecret)
                }
            }
        }
    }

    @Test
    fun `options returns correct configuration`() = runBlocking {
        TestUser.users.clear()

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            init {
                register(TestUser)
            }

            val knownDevice = path.path("auth").path("known-device") include KnownDeviceProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours,
                expires = Runtime.Constant(30.days)
            )
        }.let { server ->
            server.test({}) {
                val options = server.knownDevice.options.test(null, Unit)

                assertEquals(30.days, options.duration)
                assertEquals(3, options.strength)
            }
        }
    }

    @Test
    fun `multiple devices can be established for same user`() = runBlocking {
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

            val knownDevice = path.path("auth").path("known-device") include KnownDeviceProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                // Establish multiple devices
                val device1 = server.knownDevice.establish(TestUser, userId, "Chrome on Mac")
                val device2 = server.knownDevice.establish(TestUser, userId, "Safari on iPhone")
                val device3 = server.knownDevice.establish(TestUser, userId, "Firefox on Windows")

                // All secrets should be different
                assertNotEquals(device1.secret, device2.secret)
                assertNotEquals(device2.secret, device3.secret)
                assertNotEquals(device1.secret, device3.secret)

                // All should work for proving
                val proof1 = server.knownDevice.prove.test(null, device1.secret)
                val proof2 = server.knownDevice.prove.test(null, device2.secret)
                val proof3 = server.knownDevice.prove.test(null, device3.secret)

                // All proofs should reference the same user
                assertEquals(userId.toString(), proof1.value)
                assertEquals(userId.toString(), proof2.value)
                assertEquals(userId.toString(), proof3.value)

                // Check that 3 secrets were stored
                val secrets = server.knownDevice.modelInfo.table().find(Condition.Always).toList()
                assertEquals(3, secrets.size)
            }
        }
    }

    @Test
    fun `established always returns false`() = runBlocking {
        // KnownDeviceProofEndpoints.established always returns false because
        // we can't know if a device is "established" without the client presenting a secret
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

            val knownDevice = path.path("auth").path("known-device") include KnownDeviceProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                // Even after establishing a device, established() returns false
                server.knownDevice.establish(TestUser, userId, "Test Device")
                assertFalse(server.knownDevice.established(TestUser, user))
            }
        }
    }

    @Test
    fun `prove can be called multiple times with same secret`() = runBlocking {
        // Test that the same device secret can be used multiple times for authentication
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

            val knownDevice = path.path("auth").path("known-device") include KnownDeviceProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val established = server.knownDevice.establish(TestUser, userId, "Test Device")

                // First prove
                val proof1 = server.knownDevice.prove.test(null, established.secret)
                assertNotNull(proof1)
                assertEquals(userId.toString(), proof1.value)

                // Second prove should also work
                val proof2 = server.knownDevice.prove.test(null, established.secret)
                assertNotNull(proof2)
                assertEquals(userId.toString(), proof2.value)

                // Third prove should also work
                val proof3 = server.knownDevice.prove.test(null, established.secret)
                assertNotNull(proof3)
                assertEquals(userId.toString(), proof3.value)
            }
        }
    }

    @Test
    fun `info returns correct proof method info`() = runBlocking {
        TestUser.users.clear()

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            init {
                register(TestUser)
            }

            val knownDevice = path.path("auth").path("known-device") include KnownDeviceProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                assertEquals("known-device", server.knownDevice.info.via)
                assertEquals(null, server.knownDevice.info.property)
                assertEquals(3, server.knownDevice.info.strength)
            }
        }
    }

    @Test
    fun `secret is hashed in database`() = runBlocking {
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

            val knownDevice = path.path("auth").path("known-device") include KnownDeviceProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val established = server.knownDevice.establish(TestUser, userId, "Test Device")
                val rawSecret = established.secret.substringAfter('/')

                // The hash stored in the database should not be the plaintext secret
                val allSecrets = server.knownDevice.modelInfo.table().find(Condition.Always).toList()
                assertEquals(1, allSecrets.size)
                val stored = allSecrets.first()
                assertNotEquals(rawSecret, stored.hash)
                // The hash should not be empty
                assertTrue(stored.hash.isNotEmpty())
            }
        }
    }

    @Test
    fun `different users have independent devices`() = runBlocking {
        TestUser.users.clear()
        val userId1 = Uuid.random()
        val userId2 = Uuid.random()
        val user1 = TestUser(userId1, "user1@example.com")
        val user2 = TestUser(userId2, "user2@example.com")
        TestUser.users[userId1] = user1
        TestUser.users[userId2] = user2

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            init {
                register(TestUser)
            }

            val knownDevice = path.path("auth").path("known-device") include KnownDeviceProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val device1 = server.knownDevice.establish(TestUser, userId1, "User 1 Device")
                val device2 = server.knownDevice.establish(TestUser, userId2, "User 2 Device")

                // User 1's device should prove as user 1
                val proof1 = server.knownDevice.prove.test(null, device1.secret)
                assertEquals(userId1.toString(), proof1.value)

                // User 2's device should prove as user 2
                val proof2 = server.knownDevice.prove.test(null, device2.secret)
                assertEquals(userId2.toString(), proof2.value)

                // They should be independent
                assertNotEquals(proof1.value, proof2.value)
            }
        }
    }
}
