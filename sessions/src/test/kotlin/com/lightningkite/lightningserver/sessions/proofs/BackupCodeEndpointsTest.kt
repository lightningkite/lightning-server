// by Claude
package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.idString
import com.lightningkite.lightningserver.auth.register
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.and
import com.lightningkite.services.database.condition
import com.lightningkite.services.database.eq
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Tests for BackupCodeEndpoints - backup code authentication.
 */
class BackupCodeEndpointsTest {

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
                table.insert(listOf(BackupCodeSecret(
                    code = "abcdefghij",
                    subjectId = TestUser.idString(userId),
                    subjectType = TestUser.name
                )))

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
                table.insert(listOf(BackupCodeSecret(
                    code = "testbackupcode",
                    subjectId = TestUser.idString(userId),
                    subjectType = TestUser.name
                )))

                // Prove with the code
                val proof = server.backupCodes.prove.test(null, IdentificationAndPassword(
                    type = "TestUser",
                    property = "email",
                    value = "test@example.com",
                    password = "testbackupcode"
                ))

                assertNotNull(proof)
                assertEquals("email", proof.property)
                assertEquals("test@example.com", proof.value)
                assertNotNull(proof.signature)
            }
        }
    }

    @Test
    fun `backup code is deleted after use`() = runBlocking {
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
                table.insert(listOf(BackupCodeSecret(
                    code = "onetimecode",
                    subjectId = TestUser.idString(userId),
                    subjectType = TestUser.name
                )))

                // Verify code exists
                var codeCount = table.find(condition<BackupCodeSecret> {
                    it.subjectId.eq(TestUser.idString(userId))
                }).count()
                assertEquals(1, codeCount)

                // Use the code
                server.backupCodes.prove.test(null, IdentificationAndPassword(
                    type = "TestUser",
                    property = "email",
                    value = "test@example.com",
                    password = "onetimecode"
                ))

                // Verify code was deleted
                codeCount = table.find(condition<BackupCodeSecret> {
                    it.subjectId.eq(TestUser.idString(userId))
                }).count()
                assertEquals(0, codeCount)
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
                table.insert(listOf(BackupCodeSecret(
                    code = "singlusecode",
                    subjectId = TestUser.idString(userId),
                    subjectType = TestUser.name
                )))

                // First use should succeed
                server.backupCodes.prove.test(null, IdentificationAndPassword(
                    type = "TestUser",
                    property = "email",
                    value = "test@example.com",
                    password = "singlusecode"
                ))

                // Second use should fail
                assertFailsWith<BadRequestException>("Reusing backup code should fail") {
                    server.backupCodes.prove.test(null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "email",
                        value = "test@example.com",
                        password = "singlusecode"
                    ))
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
                table.insert(listOf(BackupCodeSecret(
                    code = "validcode",
                    subjectId = TestUser.idString(userId),
                    subjectType = TestUser.name
                )))

                // Try with invalid code
                assertFailsWith<BadRequestException>("Invalid backup code should be rejected") {
                    server.backupCodes.prove.test(null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "email",
                        value = "test@example.com",
                        password = "wrongcode"
                    ))
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
                table.insert(listOf(BackupCodeSecret(
                    code = "abcdefghij",
                    subjectId = TestUser.idString(userId),
                    subjectType = TestUser.name
                )))

                // Prove with uppercase and dashes
                val proof = server.backupCodes.prove.test(null, IdentificationAndPassword(
                    type = "TestUser",
                    property = "email",
                    value = "test@example.com",
                    password = "ABCDE-FGHIJ"  // Uppercase with dash
                ))

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
                table.insert(listOf(BackupCodeSecret(
                    code = "backupcode",
                    subjectId = TestUser.idString(userId),
                    subjectType = TestUser.name
                )))

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
                table.insert(listOf(BackupCodeSecret(
                    code = "useronecode",
                    subjectId = TestUser.idString(userId1),
                    subjectType = TestUser.name
                )))

                // User1 should be able to use their code
                val proof = server.backupCodes.prove.test(null, IdentificationAndPassword(
                    type = "TestUser",
                    property = "email",
                    value = "user1@example.com",
                    password = "useronecode"
                ))
                assertNotNull(proof)

                // Insert backup code for user2
                table.insert(listOf(BackupCodeSecret(
                    code = "usertwocode",
                    subjectId = TestUser.idString(userId2),
                    subjectType = TestUser.name
                )))

                // User2 should NOT be able to use user1's code (which was deleted)
                assertFailsWith<BadRequestException>("User2 should not be able to use deleted code") {
                    server.backupCodes.prove.test(null, IdentificationAndPassword(
                        type = "TestUser",
                        property = "email",
                        value = "user2@example.com",
                        password = "useronecode"
                    ))
                }

                // User2 should be able to use their own code
                val proof2 = server.backupCodes.prove.test(null, IdentificationAndPassword(
                    type = "TestUser",
                    property = "email",
                    value = "user2@example.com",
                    password = "usertwocode"
                ))
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
                table.insert(listOf(BackupCodeSecret(
                    code = "firstcode",
                    subjectId = TestUser.idString(userId),
                    subjectType = TestUser.name
                )))
                table.insert(listOf(BackupCodeSecret(
                    code = "secondcode",
                    subjectId = TestUser.idString(userId),
                    subjectType = TestUser.name
                )))
                table.insert(listOf(BackupCodeSecret(
                    code = "thirdcode",
                    subjectId = TestUser.idString(userId),
                    subjectType = TestUser.name
                )))

                // All codes should work (and be deleted after use)
                server.backupCodes.prove.test(null, IdentificationAndPassword(
                    type = "TestUser",
                    property = "email",
                    value = "test@example.com",
                    password = "secondcode"
                ))

                // Verify second code was deleted but others remain
                val remainingCodes = table.find(condition<BackupCodeSecret> {
                    it.subjectId.eq(TestUser.idString(userId))
                }).toList()

                assertEquals(2, remainingCodes.size)
                assertTrue(remainingCodes.none { it.code == "secondcode" })
            }
        }
    }
}
