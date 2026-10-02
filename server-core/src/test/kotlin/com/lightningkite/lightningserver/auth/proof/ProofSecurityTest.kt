package com.lightningkite.lightningserver.auth.proof

import com.lightningkite.lightningdb.*
import com.lightningkite.lightningserver.TestSettings
import com.lightningkite.lightningserver.cache.Cache
import com.lightningkite.lightningserver.cache.LocalCache
import com.lightningkite.lightningserver.email.Email
import com.lightningkite.lightningserver.email.EmailLabeledValue
import com.lightningkite.lightningserver.exceptions.BadRequestException
import com.lightningkite.lightningserver.exceptions.HttpStatusException
import com.lightningkite.lightningserver.testmodels.TestUser
import com.lightningkite.lightningserver.typed.test
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer
import kotlin.test.*

class ProofSecurityTest {
    private suspend fun newUser(email: String): TestUser =
        TestSettings.userInfo.collection().insertOne(TestUser(email = email))!!

    private suspend fun emailProof(email: String): Proof {
        lateinit var proof: Proof
        TestSettings.proofEmail.send(email) {
            proof = it
            Email(subject = "Proof", to = listOf(EmailLabeledValue(email)), plainText = "")
        }
        return proof
    }

    private suspend fun proveBackupCode(user: TestUser, code: String) = TestSettings.proofBackupCode.prove.test(
        null,
        IdentificationAndPassword(TestSettings.subjectHandler.name, "email", user.email, code)
    )

    @Test
    fun backupCodeAloneIsNotALogin(): Unit = runBlocking {
        val user = newUser("backup-alone@test.com")
        val codes = TestSettings.proofBackupCode.resetCodes.test(user, Unit)
        val backup = proveBackupCode(user, codes[0])
        assertNull(TestSettings.testUserSubject.login.test(null, listOf(backup)).session)
        val email = emailProof(user.email)
        assertNotNull(TestSettings.testUserSubject.login.test(null, listOf(email, backup)).session)
    }

    @Test
    fun backupCodesAreStoredHashedAndSingleUse(): Unit = runBlocking {
        val user = newUser("backup-hashed@test.com")
        val codes = TestSettings.proofBackupCode.resetCodes.test(user, Unit)
        val stored = TestSettings.proofBackupCode.modelInfo.collection()
            .find(condition { it.subjectId eq TestSettings.subjectHandler.idString(user._id) })
            .toList()
        assertEquals(codes.size, stored.size)
        val plainCodes = codes.map { it.filter { it.isLetter() }.lowercase() }.toSet()
        assertTrue(stored.none { it.code in plainCodes }, "Backup codes must not be stored in plain text")

        proveBackupCode(user, codes[0])
        assertFailsWith<BadRequestException> { proveBackupCode(user, codes[0]) }
    }

    @Test
    fun backupCodesStoredBeforeHashingStillWork(): Unit = runBlocking {
        val user = newUser("backup-legacy@test.com")
        TestSettings.proofBackupCode.modelInfo.collection().insertOne(
            BackupCodeSecret(
                code = "abcdefghjkabcdefghjk",
                subjectId = TestSettings.subjectHandler.idString(user._id),
                subjectType = TestSettings.subjectHandler.name,
            )
        )
        proveBackupCode(user, "ABCDE-FGHJK-ABCDE-FGHJK")
        assertFailsWith<BadRequestException> { proveBackupCode(user, "ABCDE-FGHJK-ABCDE-FGHJK") }
    }

    // "TestUser/_id" values parse case-insensitively, so differently cased IDs name the same user.
    @Test
    fun passwordRateLimitIsPerUserNotPerSpelling(): Unit = runBlocking {
        val user = newUser("password-spelling@test.com")
        TestSettings.proofPassword.establish.test(user, EstablishPassword("right"))
        val property = "${TestSettings.subjectHandler.name}/_id"
        fun guess(id: String) = IdentificationAndPassword(TestSettings.subjectHandler.name, property, id, "wrong")
        repeat(5) {
            assertFailsWith<BadRequestException> { TestSettings.proofPassword.prove.test(null, guess(user._id.toString())) }
        }
        val blocked = assertFailsWith<BadRequestException> {
            TestSettings.proofPassword.prove.test(null, guess(user._id.toString().uppercase()))
        }
        assertContains(blocked.message, "Too many attempts")
    }

    @Test
    fun unknownAccountsAreRateLimitedLikeKnownOnes(): Unit = runBlocking {
        fun guess(email: String) = IdentificationAndPassword(TestSettings.subjectHandler.name, "email", email, "wrong")
        repeat(5) {
            assertFailsWith<BadRequestException> { TestSettings.proofPassword.prove.test(null, guess("nobody-here@test.com")) }
        }
        val blocked = assertFailsWith<BadRequestException> {
            TestSettings.proofPassword.prove.test(null, guess("nobody-here@test.com"))
        }
        assertContains(blocked.message, "Too many attempts")
    }

    @Test
    fun backupCodeRateLimitIsPerUserNotPerSpelling(): Unit = runBlocking {
        val user = newUser("backup-spelling@test.com")
        TestSettings.proofBackupCode.resetCodes.test(user, Unit)
        val property = "${TestSettings.subjectHandler.name}/_id"
        fun guess(id: String) = IdentificationAndPassword(TestSettings.subjectHandler.name, property, id, "wrong")
        repeat(5) {
            assertFailsWith<BadRequestException> { TestSettings.proofBackupCode.prove.test(null, guess(user._id.toString())) }
        }
        val blocked = assertFailsWith<BadRequestException> {
            TestSettings.proofBackupCode.prove.test(null, guess(user._id.toString().uppercase()))
        }
        assertContains(blocked.message, "Too many attempts")
    }

    @Test
    fun pinStartIsLimitedPerDestinationEvenWhenSendingSucceeds(): Unit = runBlocking {
        repeat(5) { TestSettings.proofEmail.start.test(null, "pin-flood@test.com") }
        assertFailsWith<BadRequestException> { TestSettings.proofEmail.start.test(null, " PIN-Flood@test.com") }
    }

    @Test
    fun concurrentPinGuessesShareTheAttemptLimit(): Unit = runBlocking {
        // Yielding on every read lets all the guesses read the counter before any of them writes it.
        val inner = LocalCache()
        val slowReads = object : Cache by inner {
            override suspend fun <T> get(key: String, serializer: KSerializer<T>): T? {
                yield()
                return inner.get(key, serializer)
            }
        }
        val pins = PinHandler({ slowReads }, "race")
        val key = pins.establish("someone").key
        val results = (1..20).map {
            async {
                try {
                    pins.assert(key, "wrong!")
                    "accepted"
                } catch (e: HttpStatusException) {
                    e.detail
                }
            }
        }.awaitAll()
        assertTrue(results.count { it == "pin-incorrect" } <= pins.maxAttempts - 1, results.toString())
    }
}
