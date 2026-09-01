// by Claude
package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Tests for BackupCodeEndpoints - backup code authentication.
 */
class BackupCodeEndpointsTest {

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
                    else -> super.fetchByProperty(property, value)
                }
            }
        }
    }

    private val testBasis = SecretBasis()

    @Test
    fun `backup codes are generated with correct format`() = runBlocking {
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

            val backupCodes = path.path("auth").path("backup") include BackupCodeEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours,
                codeLength = 10,
                generateCount = 5
            )
        }.let { server ->
            server.test({}) {
                // Insert codes directly for testing format
                val table = server.backupCodes.modelInfo.table()
                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "abcdefghij",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now()
                        )
                    )
                )

                // Verify code was stored
                val codes = table.find(condition<BackupCodeSecret> {
                    it.subjectId.eq(TestUser.idString(userId)) and it.subjectType.eq(TestUser.name)
                }).toList()

                assertEquals(1, codes.size)
                assertEquals("abcdefghij", codes[0].code)
            }
        }
    }

    @Test
    fun `prove works with valid backup code`() = runBlocking {
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

            val backupCodes = path.path("auth").path("backup") include BackupCodeEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                // Insert a backup code
                val table = server.backupCodes.modelInfo.table()
                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "testbackupcode",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now()
                        )
                    )
                )

                // Prove with the code
                val proof = server.backupCodes.prove.test(
                    null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "email",
                        value = "test@example.com",
                        password = "testbackupcode"
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
    fun `a used backup code is retained and marked, not deleted`() = runBlocking {
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

            val backupCodes = path.path("auth").path("backup") include BackupCodeEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val table = server.backupCodes.modelInfo.table()

                // Insert a backup code
                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "onetimecode",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now()
                        )
                    )
                )

                // Verify code exists
                var codeCount = table.find(condition<BackupCodeSecret> {
                    it.subjectId.eq(TestUser.idString(userId))
                }).count()
                assertEquals(1, codeCount)

                // Use the code
                server.backupCodes.prove.test(
                    null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "email",
                        value = "test@example.com",
                        password = "onetimecode"
                    )
                )

                // The row survives — it is the only evidence the code was ever used — but is spent.
                val after = table.find(condition<BackupCodeSecret> {
                    it.subjectId.eq(TestUser.idString(userId))
                }).toList()
                assertEquals(1, after.size, "using a backup code destroyed the record that it existed")
                assertNotNull(after.single().usedAt, "a used backup code was not marked as used")

                // And it no longer counts as an established method.
                assertEquals(
                    0,
                    table.find(condition<BackupCodeSecret> {
                        it.subjectId.eq(TestUser.idString(userId)) and it.usedAt.eq(null)
                    }).count(),
                    "a spent backup code still reads as usable",
                )
            }
        }
    }

    @Test
    fun `backup code cannot be reused`() = runBlocking {
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

            val backupCodes = path.path("auth").path("backup") include BackupCodeEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val table = server.backupCodes.modelInfo.table()

                // Insert a backup code
                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "singlusecode",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now()
                        )
                    )
                )

                // First use should succeed
                server.backupCodes.prove.test(
                    null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "email",
                        value = "test@example.com",
                        password = "singlusecode"
                    )
                )

                // Second use should fail
                assertFailsWith<BadRequestException>("Reusing backup code should fail") {
                    server.backupCodes.prove.test(
                        null, IdentificationAndPassword(
                            type = "TestUser",
                            property = "email",
                            value = "test@example.com",
                            password = "singlusecode"
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `prove rejects invalid backup code`() = runBlocking {
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

            val backupCodes = path.path("auth").path("backup") include BackupCodeEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val table = server.backupCodes.modelInfo.table()

                // Insert a backup code
                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "validcode",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now()
                        )
                    )
                )

                // Try with invalid code
                assertFailsWith<BadRequestException>("Invalid backup code should be rejected") {
                    server.backupCodes.prove.test(
                        null, IdentificationAndPassword(
                            type = "TestUser",
                            property = "email",
                            value = "test@example.com",
                            password = "wrongcode"
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `prove ignores dashes and case in backup code`() = runBlocking {
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

            val backupCodes = path.path("auth").path("backup") include BackupCodeEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val table = server.backupCodes.modelInfo.table()

                // Insert a backup code (stored lowercase without dashes)
                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "abcdefghij",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now()
                        )
                    )
                )

                // Prove with uppercase and dashes
                val proof = server.backupCodes.prove.test(
                    null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "email",
                        value = "test@example.com",
                        password = "ABCDE-FGHIJ"  // Uppercase with dash
                    )
                )

                assertNotNull(proof)
                assertEquals("email", proof.property)
            }
        }
    }

    @Test
    fun `established returns true when codes exist`() = runBlocking {
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

            val backupCodes = path.path("auth").path("backup") include BackupCodeEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                // Before inserting codes, should return false
                assertFalse(server.backupCodes.established(TestUser, user))

                // Insert a backup code
                val table = server.backupCodes.modelInfo.table()
                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "backupcode",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now()
                        )
                    )
                )

                // After inserting, should return true
                assertTrue(server.backupCodes.established(TestUser, user))
            }
        }
    }

    @Test
    fun `backup codes belong to correct user`() = runBlocking {
        TestUser.users.clear()
        val userId1 = Uuid.random()
        val user1 = TestUser(userId1, "user1@example.com")
        TestUser.users[userId1] = user1

        val userId2 = Uuid.random()
        val user2 = TestUser(userId2, "user2@example.com")
        TestUser.users[userId2] = user2

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            init {
                register(TestUser)
            }

            val backupCodes = path.path("auth").path("backup") include BackupCodeEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val table = server.backupCodes.modelInfo.table()

                // Insert backup code for user1 (must be lowercase letters only since codes are normalized)
                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "useronecode",
                            subjectId = TestUser.idString(userId1),
                            subjectType = TestUser.name,
                            createdAt = now()
                        )
                    )
                )

                // User1 should be able to use their code
                val proof = server.backupCodes.prove.test(
                    null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "email",
                        value = "user1@example.com",
                        password = "useronecode"
                    )
                )
                assertNotNull(proof)

                // Insert backup code for user2
                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "usertwocode",
                            subjectId = TestUser.idString(userId2),
                            subjectType = TestUser.name,
                            createdAt = now()
                        )
                    )
                )

                // User2 should NOT be able to use user1's code (which was deleted)
                assertFailsWith<BadRequestException>("User2 should not be able to use deleted code") {
                    server.backupCodes.prove.test(
                        null, IdentificationAndPassword(
                            type = "TestUser",
                            property = "email",
                            value = "user2@example.com",
                            password = "useronecode"
                        )
                    )
                }

                // User2 should be able to use their own code
                val proof2 = server.backupCodes.prove.test(
                    null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "email",
                        value = "user2@example.com",
                        password = "usertwocode"
                    )
                )
                assertNotNull(proof2)
            }
        }
    }

    @Test
    fun `multiple backup codes can exist for same user`() = runBlocking {
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

            val backupCodes = path.path("auth").path("backup") include BackupCodeEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val table = server.backupCodes.modelInfo.table()

                // Insert multiple backup codes
                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "firstcode",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now()
                        )
                    )
                )
                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "secondcode",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now()
                        )
                    )
                )
                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "thirdcode",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now()
                        )
                    )
                )

                // All codes should work (and be marked spent after use)
                server.backupCodes.prove.test(
                    null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "email",
                        value = "test@example.com",
                        password = "secondcode"
                    )
                )

                // Every row survives; only the redeemed one is spent, and the others stay usable.
                val allCodes = table.find(condition<BackupCodeSecret> {
                    it.subjectId.eq(TestUser.idString(userId))
                }).toList()
                assertEquals(3, allCodes.size, "using one code removed rows")
                assertEquals(listOf("secondcode"), allCodes.filter { it.usedAt != null }.map { it.code })

                val stillUsable = table.find(condition<BackupCodeSecret> {
                    it.subjectId.eq(TestUser.idString(userId)) and it.usedAt.eq(null)
                }).toList()
                assertEquals(2, stillUsable.size)
                assertTrue(stillUsable.none { it.code == "secondcode" })
            }
        }
    }

    /**
     * Security regression: the rate-limit key must be built from the NORMALIZED identifier, so that
     * case/whitespace variants of one account share a single bucket. If the key were derived from the
     * raw value, an attacker could dodge the limiter (and its exponential backoff) by varying case.
     */
    @Test
    fun `rate limiter shares one bucket across case variants of the same identifier`() = runBlocking {
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

            val backupCodes = path.path("auth").path("backup") include BackupCodeEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                server.backupCodes.modelInfo.table().insert(
                    listOf(
                        BackupCodeSecret(
                            code = "validcode",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now()
                        )
                    )
                )

                // Five distinct case variants that all normalize to "test@example.com". The default limit
                // is 5 attempts; five failing attempts across these variants must fill ONE shared bucket.
                val emailVariants = listOf(
                    "Test@example.com",
                    "tEst@example.com",
                    "teSt@example.com",
                    "tesT@example.com",
                    "TEST@example.com",
                )
                for (variant in emailVariants) {
                    assertFailsWith<BadRequestException> {
                        server.backupCodes.prove.test(
                            null, IdentificationAndPassword("TestUser", "email", variant, "wrongcode")
                        )
                    }
                }

                // A sixth attempt with yet another distinct variant must be blocked by the shared limiter.
                val blocked = assertFailsWith<BadRequestException> {
                    server.backupCodes.prove.test(
                        null, IdentificationAndPassword("TestUser", "email", "TesT@example.com", "wrongcode")
                    )
                }
                assertTrue(
                    blocked.message.contains("Too many attempts"),
                    "Expected the shared rate limiter to block, but got: ${blocked.message}"
                )
            }
        }
    }
}
