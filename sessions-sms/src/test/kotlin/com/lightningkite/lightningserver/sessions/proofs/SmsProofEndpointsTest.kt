// by Claude
package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.register
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.HasId
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.sms.TestSMS
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Tests for SmsProofEndpoints - SMS-based authentication proof.
 */
class SmsProofEndpointsTest {

    @Serializable
    data class TestUser(
        override val _id: Uuid = Uuid.random(),
        val phone: String = ""
    ) : HasId<Uuid> {
        companion object : PrincipalType<TestUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<TestUser> = serializer()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): TestUser = TestUser(id)
        }
    }

    private val testBasis = SecretBasis()

    @Test
    fun `start sends SMS with PIN and returns key`() = runBlocking {
        object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings("ram"))
            val sms = setting("sms", SMS.Settings("test"))

            init {
                register(TestUser)
            }

            val smsProof = path.path("auth").path("sms") include SmsProofEndpoints(
                pin = PinHandler(
                    cache = cache,
                    keyPrefix = "sms-test-start",
                    expiration = 15.minutes,
                    maxAttempts = 5
                ),
                sms = sms,
                smsTemplate = { pin -> "Your code is: $pin" },
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val smsService = server.sms() as TestSMS
                smsService.reset()

                val key = server.smsProof.start.test(null, "+15551234567")
                assertNotNull(key)

                // Verify SMS was sent
                assertEquals(1, smsService.messageHistory.size)
                val msg = smsService.messageHistory[0]
                assertEquals("+15551234567", msg.to.raw)
                assertTrue(msg.message.contains("Your code is:"))
            }
        }
    }

    @Test
    fun `prove returns valid proof with correct PIN`() = runBlocking {
        object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings("ram"))
            val sms = setting("sms", SMS.Settings("test"))

            init {
                register(TestUser)
            }

            val smsProof = path.path("auth").path("sms") include SmsProofEndpoints(
                pin = PinHandler(
                    cache = cache,
                    keyPrefix = "sms-test-prove",
                    expiration = 15.minutes,
                    maxAttempts = 5
                ),
                sms = sms,
                smsTemplate = { pin -> "Your code is: $pin" },
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val smsService = server.sms() as TestSMS
                smsService.reset()

                // Start the proof
                val key = server.smsProof.start.test(null, "+15551234567")

                // Extract PIN from sent SMS
                val msg = smsService.messageHistory[0]
                val pinMatch = Regex("Your code is: ([A-Z0-9]+)").find(msg.message)!!
                val pin = pinMatch.groupValues[1]

                // Prove with correct PIN
                val proof = server.smsProof.prove.test(null, FinishProof(key, pin))

                assertNotNull(proof)
                assertEquals("phone", proof.property)
                assertEquals("+15551234567", proof.value)
                assertNotNull(proof.signature)
            }
        }
    }

    @Test
    fun `phone number is normalized to E164`() = runBlocking {
        object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings("ram"))
            val sms = setting("sms", SMS.Settings("test"))

            init {
                register(TestUser)
            }

            val smsProof = path.path("auth").path("sms") include SmsProofEndpoints(
                pin = PinHandler(
                    cache = cache,
                    keyPrefix = "sms-test-normalize",
                    expiration = 15.minutes,
                    maxAttempts = 5
                ),
                sms = sms,
                smsTemplate = { pin -> "Your code is: $pin" },
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val smsService = server.sms() as TestSMS
                smsService.reset()

                // Start with various phone formats
                server.smsProof.start.test(null, "555-123-4567")

                // Should be normalized to E.164 with +1 prefix for 10-digit US numbers
                val msg = smsService.messageHistory[0]
                assertEquals("+15551234567", msg.to.raw)
            }
        }
    }

    @Test
    fun `10-digit US number gets +1 prefix`() = runBlocking {
        object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings("ram"))
            val sms = setting("sms", SMS.Settings("test"))

            init {
                register(TestUser)
            }

            val smsProof = path.path("auth").path("sms") include SmsProofEndpoints(
                pin = PinHandler(
                    cache = cache,
                    keyPrefix = "sms-test-us-prefix",
                    expiration = 15.minutes,
                    maxAttempts = 5
                ),
                sms = sms,
                smsTemplate = { pin -> "Your code is: $pin" },
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val smsService = server.sms() as TestSMS
                smsService.reset()

                // 10-digit number should get +1 prefix
                server.smsProof.start.test(null, "5551234567")

                val msg = smsService.messageHistory[0]
                assertEquals("+15551234567", msg.to.raw)
            }
        }
    }

    @Test
    fun `international number preserves country code`() = runBlocking {
        object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings("ram"))
            val sms = setting("sms", SMS.Settings("test"))

            init {
                register(TestUser)
            }

            val smsProof = path.path("auth").path("sms") include SmsProofEndpoints(
                pin = PinHandler(
                    cache = cache,
                    keyPrefix = "sms-test-intl",
                    expiration = 15.minutes,
                    maxAttempts = 5
                ),
                sms = sms,
                smsTemplate = { pin -> "Your code is: $pin" },
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val smsService = server.sms() as TestSMS
                smsService.reset()

                // UK number with country code
                server.smsProof.start.test(null, "+44 20 7946 0958")

                val msg = smsService.messageHistory[0]
                assertEquals("+442079460958", msg.to.raw)
            }
        }
    }

    @Test
    fun `multiple starts generate different keys`() = runBlocking {
        object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings("ram"))
            val sms = setting("sms", SMS.Settings("test"))

            init {
                register(TestUser)
            }

            val smsProof = path.path("auth").path("sms") include SmsProofEndpoints(
                pin = PinHandler(
                    cache = cache,
                    keyPrefix = "sms-test-multiple",
                    expiration = 15.minutes,
                    maxAttempts = 5
                ),
                sms = sms,
                smsTemplate = { pin -> "Your code is: $pin" },
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val smsService = server.sms() as TestSMS
                smsService.reset()

                val key1 = server.smsProof.start.test(null, "+15551234567")
                val key2 = server.smsProof.start.test(null, "+15551234567")

                assertTrue(key1 != key2, "Different start calls should generate different keys")
                assertEquals(2, smsService.messageHistory.size, "Each start should send an SMS")
            }
        }
    }

    @Test
    fun `extension is stripped from phone number`() = runBlocking {
        object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings("ram"))
            val sms = setting("sms", SMS.Settings("test"))

            init {
                register(TestUser)
            }

            val smsProof = path.path("auth").path("sms") include SmsProofEndpoints(
                pin = PinHandler(
                    cache = cache,
                    keyPrefix = "sms-test-extension",
                    expiration = 15.minutes,
                    maxAttempts = 5
                ),
                sms = sms,
                smsTemplate = { pin -> "Your code is: $pin" },
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val smsService = server.sms() as TestSMS
                smsService.reset()

                // Phone number with extension should have extension stripped
                server.smsProof.start.test(null, "5551234567x123")

                val msg = smsService.messageHistory[0]
                assertEquals("+15551234567", msg.to.raw)
            }
        }
    }
}
