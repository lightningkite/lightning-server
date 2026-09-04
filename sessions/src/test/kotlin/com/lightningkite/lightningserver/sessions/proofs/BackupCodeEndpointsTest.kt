// by Claude
package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.http.HttpHeaders
import com.lightningkite.lightningserver.http.HttpRequest
import com.lightningkite.lightningserver.http.HttpStatus
import com.lightningkite.lightningserver.http.QueryParameters
import com.lightningkite.lightningserver.http.generateRequestId
import com.lightningkite.lightningserver.pathing.PathSpec
import com.lightningkite.lightningserver.pathing.RawHttpEndpoint
import com.lightningkite.lightningserver.runtime.Engine
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.now
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.sessions.proofs.extensions.TooManyAttemptsException
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
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
 * A [Database] that lets a test run something in the middle of a read of one table.
 *
 * Concurrency cannot be pinned down by launching two coroutines and hoping they interleave; the
 * outcome would depend on the scheduler and the test would discriminate nothing reliably. So the
 * interleaving is staged instead: [nextInterleave] is consulted on each read of [tableName] and, if
 * it yields an action, that action runs to completion *after* the rows have been fetched but
 * *before* the caller receives them. The caller therefore proceeds on a view of the table that is
 * already out of date — precisely the window an atomic conditional write has to close.
 *
 * The rows are materialized before the action runs so that the caller sees the pre-action state even
 * on a backend whose result flow is lazy over live storage.
 */
private class InterleavingDatabase(
    private val wraps: Database,
    private val tableName: String,
    private val nextInterleave: () -> (suspend () -> Unit)?,
) : Database by wraps {

    override fun <T : Any> table(tableDef: DatabaseTableDefinition<T>): Table<T> =
        decorate(tableDef, wraps.table(tableDef))

    override suspend fun <T : Any> prepare(tableDef: DatabaseTableDefinition<T>): Table<T> =
        decorate(tableDef, wraps.prepare(tableDef))

    private fun <T : Any> decorate(tableDef: DatabaseTableDefinition<T>, table: Table<T>): Table<T> =
        if (tableDef.name != tableName) table
        else object : Table<T> by table {
            override suspend fun find(
                condition: Condition<T>,
                orderBy: List<SortPart<T>>,
                skip: Int,
                limit: Int,
                maxQueryMs: Long,
            ): Flow<T> {
                val rows = table.find(condition, orderBy, skip, limit, maxQueryMs).toList()
                nextInterleave()?.invoke()
                return rows.asFlow()
            }
        }
}

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
     * Redeeming a backup code claims it with a conditional update whose condition includes that the
     * code is still unspent. That condition is the whole of the protection against two redemptions of
     * the same code both succeeding; a plain read-then-write would satisfy every other test in this
     * file, because they are all sequential, and the flaw only appears when one redemption reads the
     * code before another has finished spending it.
     *
     * So the interleaving is staged rather than raced for: a second, complete redemption runs inside
     * the window between the first one's read and its write. Against the conditional update the first
     * redemption then loses on the condition and is rejected exactly as a replay would be; against a
     * read-then-write it would overwrite the second one's mark and both would be handed a proof.
     */
    @Test
    fun `two interleaved redemptions of one backup code cannot both succeed`() = runBlocking {
        TestUser.users.clear()
        val userId = Uuid.random()
        TestUser.users[userId] = TestUser(userId, "test@example.com")

        // Armed just before the contested redemption and consumed by the first read, so the setup and
        // verification queries below are unaffected.
        var interleave: (suspend () -> Unit)? = null

        object : ServerBuilder() {
            val rawDatabase = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            init {
                register(TestUser)
            }

            val database: Runtime<Database> = object : Runtime<Database> {
                context(server: Engine)
                override fun invoke(): Database =
                    InterleavingDatabase(rawDatabase(), "BackupCodeSecret") {
                        val next = interleave
                        interleave = null
                        next
                    }
            }

            val backupCodes = path.path("auth").path("backup") include BackupCodeEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours,
            )
        }.let { server ->
            server.test({}) {
                val table = server.backupCodes.modelInfo.table()
                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "contestedcode",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now(),
                        )
                    )
                )
                assertEquals(
                    1,
                    table.find(condition<BackupCodeSecret> { it.usedAt.eq(null) }).count(),
                    "there is no live code to contest",
                )

                val request = IdentificationAndPassword(
                    type = "TestUser",
                    property = "email",
                    value = "test@example.com",
                    password = "contestedcode",
                )

                var interleavedProof: Proof? = null
                interleave = { interleavedProof = server.backupCodes.prove.test(null, request) }

                val rejected = assertFailsWith<BadRequestException>(
                    "one backup code was redeemed twice"
                ) { server.backupCodes.prove.test(null, request) }

                // The interleaved redemption is the whole fixture. If it never ran, nothing was
                // contested and the rejection above would prove nothing.
                assertNotNull(interleavedProof, "the interleaved redemption never ran")
                assertNull(interleave, "the interleave was never consumed")
                assertFalse(
                    rejected is TooManyAttemptsException,
                    "the rejection came from the rate limiter, not from losing the claim",
                )

                val after = table.find(Condition.Always).toList()
                assertEquals(1, after.size, "the contested code's row did not survive")
                assertNotNull(after.single().usedAt, "the code that produced a proof was not marked spent")
            }
        }
    }

    // ========== Revocation: resetCodes and clearCodes ==========

    /**
     * The proof endpoints are declared against `HasId<*>` rather than a concrete principal, so a
     * [TestUser] authentication has to be widened to be handed to one. Nothing about the
     * authentication changes; only its static type.
     */
    @Suppress("UNCHECKED_CAST")
    private fun Authentication<TestUser>.forEndpoint(): Authentication<HasId<*>> =
        this as Authentication<HasId<*>>

    /**
     * Revocation withdraws secrets; it is not a way to erase history. A spent row is the record of an
     * authentication that happened, and the `usedAt == null` guard on the delete is the only thing
     * keeping that record out of the blast radius — without it, resetting your codes quietly destroys
     * the evidence of every code you ever redeemed.
     */
    @Test
    fun `resetCodes withdraws live codes and keeps the record of spent ones`() = runBlocking {
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
                generateCount = 5,
            )
        }.let { server ->
            server.test({}) {
                val table = server.backupCodes.modelInfo.table()
                val spentAt = now()

                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "liveonecode",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now(),
                        ),
                        BackupCodeSecret(
                            code = "livetwocode",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now(),
                        ),
                        BackupCodeSecret(
                            code = "alreadyspent",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now(),
                            usedAt = spentAt,
                        ),
                    )
                )

                // The fixture has to actually be in the state the test claims, or every assertion
                // below would hold just as well against an empty table.
                val before = table.find(condition<BackupCodeSecret> {
                    it.subjectId.eq(TestUser.idString(userId))
                }).toList()
                assertEquals(3, before.size)
                assertEquals(2, before.count { it.usedAt == null })

                val auth = Authentication(TestUser, userId, sessionId = null).forEndpoint()
                val issued = server.backupCodes.resetCodes.test(auth, Unit)
                assertEquals(5, issued.size, "reset did not issue a fresh set of codes")

                val after = table.find(condition<BackupCodeSecret> {
                    it.subjectId.eq(TestUser.idString(userId))
                }).toList()

                val spent = after.filter { it.usedAt != null }
                assertEquals(listOf("alreadyspent"), spent.map { it.code }, "revocation erased a redemption record")
                assertEquals(spentAt, spent.single().usedAt, "the redemption's timestamp did not survive revocation")

                val live = after.filter { it.usedAt == null }
                assertEquals(5, live.size, "the old live codes were not replaced by exactly the new ones")
                assertTrue(
                    live.none { it.code == "liveonecode" || it.code == "livetwocode" },
                    "a revoked code is still usable",
                )
            }
        }
    }

    /** As [resetCodes withdraws live codes and keeps the record of spent ones], for the endpoint that withdraws without reissuing. */
    @Test
    fun `clearCodes withdraws live codes and keeps the record of spent ones`() = runBlocking {
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
            )
        }.let { server ->
            server.test({}) {
                val table = server.backupCodes.modelInfo.table()
                val spentAt = now()

                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "stillusable",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now(),
                        ),
                        BackupCodeSecret(
                            code = "alreadyspent",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now(),
                            usedAt = spentAt,
                        ),
                    )
                )
                val before = table.find(condition<BackupCodeSecret> {
                    it.subjectId.eq(TestUser.idString(userId))
                }).toList()
                assertEquals(2, before.size)
                assertEquals(1, before.count { it.usedAt == null })

                val auth = Authentication(TestUser, userId, sessionId = null).forEndpoint()
                server.backupCodes.clearCodes.test(auth, Unit)

                val after = table.find(condition<BackupCodeSecret> {
                    it.subjectId.eq(TestUser.idString(userId))
                }).toList()
                assertEquals(
                    listOf("alreadyspent"),
                    after.map { it.code },
                    "clearing should leave exactly the spent rows behind",
                )
                assertEquals(spentAt, after.single().usedAt, "the redemption's timestamp did not survive clearing")
                assertFalse(
                    server.backupCodes.established(TestUser, user),
                    "a cleared user still reads as having backup codes established",
                )
            }
        }
    }

    /**
     * These endpoints delete credentials, and the condition they delete by is the whole of the
     * authorization: drop the subject from it and any authenticated caller wipes everyone's codes.
     */
    @Test
    fun `revocation does not reach another subject's codes`() = runBlocking {
        TestUser.users.clear()
        val ownerId = Uuid.random()
        TestUser.users[ownerId] = TestUser(ownerId, "owner@example.com")
        val otherId = Uuid.random()
        TestUser.users[otherId] = TestUser(otherId, "other@example.com")

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
                generateCount = 5,
            )
        }.let { server ->
            server.test({}) {
                val table = server.backupCodes.modelInfo.table()
                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "ownerscodeone",
                            subjectId = TestUser.idString(ownerId),
                            subjectType = TestUser.name,
                            createdAt = now(),
                        ),
                        BackupCodeSecret(
                            code = "ownerscodetwo",
                            subjectId = TestUser.idString(ownerId),
                            subjectType = TestUser.name,
                            createdAt = now(),
                        ),
                    )
                )
                val ownersLiveCodes = condition<BackupCodeSecret> {
                    it.subjectId.eq(TestUser.idString(ownerId)) and it.usedAt.eq(null)
                }
                assertEquals(
                    listOf("ownerscodeone", "ownerscodetwo"),
                    table.find(ownersLiveCodes).toList().map { it.code }.sorted(),
                )

                val otherAuth = Authentication(TestUser, otherId, sessionId = null).forEndpoint()
                server.backupCodes.resetCodes.test(otherAuth, Unit)
                assertEquals(
                    listOf("ownerscodeone", "ownerscodetwo"),
                    table.find(ownersLiveCodes).toList().map { it.code }.sorted(),
                    "resetting one subject's codes revoked another subject's",
                )

                server.backupCodes.clearCodes.test(otherAuth, Unit)
                assertEquals(
                    listOf("ownerscodeone", "ownerscodetwo"),
                    table.find(ownersLiveCodes).toList().map { it.code }.sorted(),
                    "clearing one subject's codes revoked another subject's",
                )
            }
        }
    }

    /**
     * The scoping above only protects anyone if the caller has to be authenticated at all, so this
     * goes through the real request pipeline rather than the typed `test` helper — the helper hands
     * the endpoint an authentication directly and never consults its [proofMethodAuth] requirement.
     */
    @Test
    fun `the revocation endpoints refuse an unauthenticated caller`() = runBlocking {
        TestUser.users.clear()
        val userId = Uuid.random()
        TestUser.users[userId] = TestUser(userId, "test@example.com")

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))
            val cache = setting("cache", Cache.Settings("ram"))

            init {
                register(TestUser)
                registerBasicMediaTypeCoders()
            }

            val backupCodes = path.path("auth").path("backup") include BackupCodeEndpoints(
                database = database,
                cache = cache,
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours,
            )
        }.let { server ->
            server.test({}) {
                val table = server.backupCodes.modelInfo.table()
                table.insert(
                    listOf(
                        BackupCodeSecret(
                            code = "targetedcode",
                            subjectId = TestUser.idString(userId),
                            subjectType = TestUser.name,
                            createdAt = now(),
                        )
                    )
                )
                assertEquals(1, table.find(Condition.Always).count())

                // What is required is not merely "some authenticated caller" but a recent proof of this
                // very method — the same bar as changing the credential these codes back up.
                assertEquals(server.backupCodes.proofMethodAuth, server.backupCodes.resetCodes.auth)
                assertEquals(server.backupCodes.proofMethodAuth, server.backupCodes.clearCodes.auth)

                for (endpoint in listOf("reset-codes", "clear-codes")) {
                    val response = runBlocking {
                        serverRuntime.handle(
                            HttpRequest<PathSpec>(
                                path = RawHttpEndpoint(asString = "/auth/backup/$endpoint", method = HttpMethod.POST),
                                queryParameters = QueryParameters.EMPTY,
                                headers = HttpHeaders(),
                                domain = "example.com",
                                protocol = "https",
                                sourceIp = "203.0.113.7",
                            ),
                            generateRequestId(),
                        )
                    }
                    // Forbidden rather than Unauthorized is what AuthRequirement.assert raises for a
                    // caller with no authentication at all; what matters here is that it refuses.
                    assertEquals(
                        HttpStatus.Forbidden,
                        response.status,
                        "/auth/backup/$endpoint answered an unauthenticated caller with ${response.status}",
                    )
                }

                assertEquals(
                    1,
                    table.find(Condition.Always).count(),
                    "an unauthenticated request revoked a code anyway",
                )
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
