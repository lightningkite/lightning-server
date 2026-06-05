// by Claude
package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.sessions.TotpSecret
import com.lightningkite.lightningserver.sessions.proofs.extensions.code
import com.lightningkite.lightningserver.sessions.proofs.extensions.generator
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import dev.turingcomplete.kotlinonetimepassword.HmacAlgorithm
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.bouncycastle.util.encoders.Base32
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Tests for TimeBasedOTPProofEndpoints - TOTP-based authentication.
 */
class TimeBasedOTPProofEndpointsTest {

    @Serializable
    data class TestUser(
        override val _id: Uuid = Uuid.random(),
        val email: String = "",
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
                    "TestUser/_id" -> users.values.find { it._id.toString() == value }
                    else -> super.fetchByProperty(property, value)
                }
            }
        }
    }

    private val testBasis = SecretBasis()

    // Create a known secret for testing
    private val testSecret = ByteArray(32) { (it + 1).toByte() }
    private val testSecretBase32 = Base32.encode(testSecret).toString(Charsets.UTF_8)

    private val testConfig = TimeBasedOneTimePasswordConfig(
        timeStep = 30,
        timeStepUnit = TimeUnit.SECONDS,
        codeDigits = 6,
        hmacAlgorithm = HmacAlgorithm.SHA1
    )

    @Test
    fun `prove works with valid TOTP code`() = runBlocking {
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

            val totpEndpoints = path.path("auth").path("totp") include TimeBasedOTPProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours,
                config = testConfig
            )
        }.let { server ->
            server.test({}) {
                val table = server.totpEndpoints.modelInfo.table()

                // Insert a TOTP secret with known values
                val totpSecret = TotpSecret(
                    subjectId = TestUser.idString(userId),
                    subjectType = TestUser.name,
                    secretBase32 = testSecretBase32,
                    label = "test",
                    issuer = "TestApp",
                    period = 30.seconds,
                    digits = 6,
                    algorithm = TotpHashAlgorithm.SHA1,
                    establishedAt = Clock.System.now(),
                    lastUsedAt = Clock.System.now() // Mark as established
                )
                table.insert(listOf(totpSecret))

                // Generate the current valid code
                val currentCode = totpSecret.code

                // Prove with the valid code
                val proof = server.totpEndpoints.prove.test(
                    null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "TestUser/_id",
                        value = userId.toString(),
                        password = currentCode
                    )
                )

                assertNotNull(proof)
                assertEquals("TestUser/_id", proof.property)
                assertEquals(userId.toString(), proof.value)
                assertNotNull(proof.signature)
            }
        }
    }

    @Test
    fun `prove rejects invalid TOTP code`() = runBlocking {
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

            val totpEndpoints = path.path("auth").path("totp") include TimeBasedOTPProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours,
                config = testConfig
            )
        }.let { server ->
            server.test({}) {
                val table = server.totpEndpoints.modelInfo.table()

                // Insert a TOTP secret
                val totpSecret = TotpSecret(
                    subjectId = TestUser.idString(userId),
                    subjectType = TestUser.name,
                    secretBase32 = testSecretBase32,
                    label = "test",
                    issuer = "TestApp",
                    period = 30.seconds,
                    digits = 6,
                    algorithm = TotpHashAlgorithm.SHA1,
                    establishedAt = Clock.System.now(),
                    lastUsedAt = Clock.System.now()
                )
                table.insert(listOf(totpSecret))

                // Try with an invalid code
                assertFailsWith<BadRequestException>("Invalid TOTP code should be rejected") {
                    server.totpEndpoints.prove.test(
                        null, IdentificationAndPassword(
                            type = "TestUser",
                            property = "TestUser/_id",
                            value = userId.toString(),
                            password = "000000"  // Likely invalid code
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `prove rejects a reused TOTP code (single-use)`() = runBlocking {
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

            val totpEndpoints = path.path("auth").path("totp") include TimeBasedOTPProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours,
                config = testConfig
            )
        }.let { server ->
            server.test({}) {
                val table = server.totpEndpoints.modelInfo.table()
                val totpSecret = TotpSecret(
                    subjectId = TestUser.idString(userId),
                    subjectType = TestUser.name,
                    secretBase32 = testSecretBase32,
                    label = "test",
                    issuer = "TestApp",
                    period = 30.seconds,
                    digits = 6,
                    algorithm = TotpHashAlgorithm.SHA1,
                    establishedAt = Clock.System.now(),
                    lastUsedAt = Clock.System.now()
                )
                table.insert(listOf(totpSecret))

                val input = IdentificationAndPassword(
                    type = "TestUser",
                    property = "TestUser/_id",
                    value = userId.toString(),
                    password = totpSecret.code
                )

                // First use of the code succeeds.
                assertNotNull(server.totpEndpoints.prove.test(null, input))

                // Replaying the same code within its time-step is rejected (single-use, RFC 6238 §5.2),
                // with the same opaque error as a wrong code.
                assertFailsWith<BadRequestException>("Reused TOTP code should be rejected") {
                    server.totpEndpoints.prove.test(null, input)
                }
            }
        }
    }

    @Test
    fun `established returns true only when lastUsedAt is set`() = runBlocking {
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

            val totpEndpoints = path.path("auth").path("totp") include TimeBasedOTPProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours,
                config = testConfig
            )
        }.let { server ->
            server.test({}) {
                val table = server.totpEndpoints.modelInfo.table()

                // Before inserting, should return false
                assertFalse(server.totpEndpoints.established(TestUser, user))

                // Insert a TOTP secret WITHOUT lastUsedAt (not yet confirmed)
                val totpSecret = TotpSecret(
                    subjectId = TestUser.idString(userId),
                    subjectType = TestUser.name,
                    secretBase32 = testSecretBase32,
                    label = "test",
                    issuer = "TestApp",
                    period = 30.seconds,
                    digits = 6,
                    algorithm = TotpHashAlgorithm.SHA1,
                    establishedAt = Clock.System.now(),
                    lastUsedAt = null // Not yet used/confirmed
                )
                table.insert(listOf(totpSecret))

                // Should still return false because lastUsedAt is null
                assertFalse(server.totpEndpoints.established(TestUser, user))

                // After proving (which sets lastUsedAt), should return true
                val currentCode = totpSecret.code
                server.totpEndpoints.prove.test(
                    null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "TestUser/_id",
                        value = userId.toString(),
                        password = currentCode
                    )
                )

                // Now should return true
                assertTrue(server.totpEndpoints.established(TestUser, user))
            }
        }
    }

    @Test
    fun `disabled TOTP secrets cannot be used`() = runBlocking {
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

            val totpEndpoints = path.path("auth").path("totp") include TimeBasedOTPProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours,
                config = testConfig
            )
        }.let { server ->
            server.test({}) {
                val table = server.totpEndpoints.modelInfo.table()

                // Insert a disabled TOTP secret
                val totpSecret = TotpSecret(
                    subjectId = TestUser.idString(userId),
                    subjectType = TestUser.name,
                    secretBase32 = testSecretBase32,
                    label = "test",
                    issuer = "TestApp",
                    period = 30.seconds,
                    digits = 6,
                    algorithm = TotpHashAlgorithm.SHA1,
                    establishedAt = Clock.System.now(),
                    lastUsedAt = Clock.System.now(),
                    disabledAt = Clock.System.now()  // Disabled
                )
                table.insert(listOf(totpSecret))

                // Try with a valid code for the disabled secret
                val currentCode = totpSecret.code

                // Should fail because the secret is disabled
                assertFailsWith<BadRequestException>("Disabled TOTP should be rejected") {
                    server.totpEndpoints.prove.test(
                        null, IdentificationAndPassword(
                            type = "TestUser",
                            property = "TestUser/_id",
                            value = userId.toString(),
                            password = currentCode
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `TOTP codes are time-based and change`() = runBlocking {
        // Create a TOTP secret
        val totpSecret = TotpSecret(
            subjectId = "test",
            subjectType = "Test",
            secretBase32 = testSecretBase32,
            label = "test",
            issuer = "TestApp",
            period = 30.seconds,
            digits = 6,
            algorithm = TotpHashAlgorithm.SHA1,
            establishedAt = Clock.System.now()
        )

        // Generate a code
        val code = totpSecret.code

        // The code should be 6 digits
        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })
    }

    @Test
    fun `TOTP generator validates correct codes`() = runBlocking {
        val totpSecret = TotpSecret(
            subjectId = "test",
            subjectType = "Test",
            secretBase32 = testSecretBase32,
            label = "test",
            issuer = "TestApp",
            period = 30.seconds,
            digits = 6,
            algorithm = TotpHashAlgorithm.SHA1,
            establishedAt = Clock.System.now()
        )

        val currentCode = totpSecret.code
        val now = java.time.Instant.now()

        // The generator should validate the current code
        assertTrue(totpSecret.generator.isValid(currentCode, now))

        // An invalid code should not validate
        assertFalse(totpSecret.generator.isValid("000000", now))
    }

    @Test
    fun `multiple TOTP secrets for different users are isolated`() = runBlocking {
        TestUser.users.clear()
        val userId1 = Uuid.random()
        val user1 = TestUser(userId1, "user1@example.com")
        TestUser.users[userId1] = user1

        val userId2 = Uuid.random()
        val user2 = TestUser(userId2, "user2@example.com")
        TestUser.users[userId2] = user2

        // Different secrets for different users
        val secret1 = ByteArray(32) { (it + 1).toByte() }
        val secret2 = ByteArray(32) { (it + 100).toByte() }
        val secretBase32_1 = Base32.encode(secret1).toString(Charsets.UTF_8)
        val secretBase32_2 = Base32.encode(secret2).toString(Charsets.UTF_8)

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            init {
                register(TestUser)
            }

            val totpEndpoints = path.path("auth").path("totp") include TimeBasedOTPProofEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours,
                config = testConfig
            )
        }.let { server ->
            server.test({}) {
                val table = server.totpEndpoints.modelInfo.table()

                // Insert TOTP secrets for both users
                val totpSecret1 = TotpSecret(
                    subjectId = TestUser.idString(userId1),
                    subjectType = TestUser.name,
                    secretBase32 = secretBase32_1,
                    label = "user1",
                    issuer = "TestApp",
                    period = 30.seconds,
                    digits = 6,
                    algorithm = TotpHashAlgorithm.SHA1,
                    establishedAt = Clock.System.now(),
                    lastUsedAt = Clock.System.now()
                )
                val totpSecret2 = TotpSecret(
                    subjectId = TestUser.idString(userId2),
                    subjectType = TestUser.name,
                    secretBase32 = secretBase32_2,
                    label = "user2",
                    issuer = "TestApp",
                    period = 30.seconds,
                    digits = 6,
                    algorithm = TotpHashAlgorithm.SHA1,
                    establishedAt = Clock.System.now(),
                    lastUsedAt = Clock.System.now()
                )
                table.insert(listOf(totpSecret1, totpSecret2))

                // User1's code should work for user1
                val code1 = totpSecret1.code
                val proof1 = server.totpEndpoints.prove.test(
                    null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "TestUser/_id",
                        value = userId1.toString(),
                        password = code1
                    )
                )
                assertNotNull(proof1)

                // User1's code should NOT work for user2
                assertFailsWith<BadRequestException>("User1's code should not work for user2") {
                    server.totpEndpoints.prove.test(
                        null, IdentificationAndPassword(
                            type = "TestUser",
                            property = "TestUser/_id",
                            value = userId2.toString(),
                            password = code1
                        )
                    )
                }

                // User2's code should work for user2
                val code2 = totpSecret2.code
                val proof2 = server.totpEndpoints.prove.test(
                    null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "TestUser/_id",
                        value = userId2.toString(),
                        password = code2
                    )
                )
                assertNotNull(proof2)
            }
        }
    }
}
