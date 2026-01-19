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
import com.lightningkite.services.email.Email
import com.lightningkite.services.email.EmailAddressWithName
import com.lightningkite.services.email.EmailService
import com.lightningkite.services.email.TestEmailService
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
 * Tests for EmailProofEndpoints - email-based authentication proof.
 */
class EmailProofEndpointsTest {

    @Serializable
    data class TestUser(
        override val _id: Uuid = Uuid.random(),
        val email: String = ""
    ) : HasId<Uuid> {
        companion object : PrincipalType<TestUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<TestUser> = serializer()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): TestUser = TestUser(id)
        }
    }

    private val testBasis = SecretBasis()

    object TestServer1 : ServerBuilder() {
        val cache = setting("cache", Cache.Settings("ram"))
        val email = setting("email", EmailService.Settings("test"))

        init {
            register(TestUser)
        }
    }

    // Separate servers for each test to avoid cache key collisions
    object TestServer2 : ServerBuilder() {
        val cache = setting("cache", Cache.Settings("ram"))
        val email = setting("email", EmailService.Settings("test"))

        init {
            register(TestUser)
        }
    }

    object TestServer3 : ServerBuilder() {
        val cache = setting("cache", Cache.Settings("ram"))
        val email = setting("email", EmailService.Settings("test"))

        init {
            register(TestUser)
        }
    }

    object TestServer4 : ServerBuilder() {
        val cache = setting("cache", Cache.Settings("ram"))
        val email = setting("email", EmailService.Settings("test"))

        init {
            register(TestUser)
        }
    }

    @Test
    fun `start sends email with PIN and returns key`() = runBlocking {
        object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings("ram"))
            val email = setting("email", EmailService.Settings("test"))

            init {
                register(TestUser)
            }

            val emailProof = path.path("auth").path("email") include EmailProofEndpoints(
                pin = PinHandler(
                    cache = cache,
                    keyPrefix = "email-test-start",
                    expiration = 15.minutes,
                    maxAttempts = 5
                ),
                email = email,
                emailTemplate = { recipient, pin ->
                    Email(
                        subject = "Your verification code",
                        to = listOf(EmailAddressWithName(recipient)),
                        plainText = "Your code is: $pin"
                    )
                },
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val emailService = server.email() as TestEmailService
                emailService.clear()

                val key = server.emailProof.start.test(null, "user@example.com")
                assertNotNull(key)

                // Verify email was sent
                assertEquals(1, emailService.sentEmails.size)
                val email = emailService.sentEmails[0]
                assertEquals("user@example.com", email.to.first().value.raw)
                assertTrue(email.plainText.contains("Your code is:"))
            }
        }
    }

    @Test
    fun `prove returns valid proof with correct PIN`() = runBlocking {
        object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings("ram"))
            val email = setting("email", EmailService.Settings("test"))

            init {
                register(TestUser)
            }

            val emailProof = path.path("auth").path("email") include EmailProofEndpoints(
                pin = PinHandler(
                    cache = cache,
                    keyPrefix = "email-test-prove",
                    expiration = 15.minutes,
                    maxAttempts = 5
                ),
                email = email,
                emailTemplate = { recipient, pin ->
                    Email(
                        subject = "Your verification code",
                        to = listOf(EmailAddressWithName(recipient)),
                        plainText = "Your code is: $pin"
                    )
                },
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val emailService = server.email() as TestEmailService
                emailService.clear()

                // Start the proof
                val key = server.emailProof.start.test(null, "user@example.com")

                // Extract PIN from sent email
                val email = emailService.sentEmails[0]
                val pinMatch = Regex("Your code is: ([A-Z0-9]+)").find(email.plainText)!!
                val pin = pinMatch.groupValues[1]

                // Prove with correct PIN
                val proof = server.emailProof.prove.test(null, FinishProof(key, pin))

                assertNotNull(proof)
                assertEquals("email", proof.property)
                assertEquals("user@example.com", proof.value)
                assertNotNull(proof.signature)
            }
        }
    }

    @Test
    fun `email is normalized to lowercase`() = runBlocking {
        object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings("ram"))
            val email = setting("email", EmailService.Settings("test"))

            init {
                register(TestUser)
            }

            val emailProof = path.path("auth").path("email") include EmailProofEndpoints(
                pin = PinHandler(
                    cache = cache,
                    keyPrefix = "email-test-normalize",
                    expiration = 15.minutes,
                    maxAttempts = 5
                ),
                email = email,
                emailTemplate = { recipient, pin ->
                    Email(
                        subject = "Your verification code",
                        to = listOf(EmailAddressWithName(recipient)),
                        plainText = "Your code is: $pin"
                    )
                },
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val emailService = server.email() as TestEmailService
                emailService.clear()

                // Start with uppercase email
                server.emailProof.start.test(null, "USER@EXAMPLE.COM")

                // Email should be sent to lowercase version
                val email = emailService.sentEmails[0]
                assertEquals("user@example.com", email.to.first().value.raw)
            }
        }
    }

    @Test
    fun `multiple starts generate different keys`() = runBlocking {
        object : ServerBuilder() {
            val cache = setting("cache", Cache.Settings("ram"))
            val email = setting("email", EmailService.Settings("test"))

            init {
                register(TestUser)
            }

            val emailProof = path.path("auth").path("email") include EmailProofEndpoints(
                pin = PinHandler(
                    cache = cache,
                    keyPrefix = "email-test-multiple",
                    expiration = 15.minutes,
                    maxAttempts = 5
                ),
                email = email,
                emailTemplate = { recipient, pin ->
                    Email(
                        subject = "Your verification code",
                        to = listOf(EmailAddressWithName(recipient)),
                        plainText = "Your code is: $pin"
                    )
                },
                proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
                proofExpiration = 1.hours
            )
        }.let { server ->
            server.test({}) {
                val emailService = server.email() as TestEmailService
                emailService.clear()

                val key1 = server.emailProof.start.test(null, "user@example.com")
                val key2 = server.emailProof.start.test(null, "user@example.com")

                assertTrue(key1 != key2, "Different start calls should generate different keys")
                assertEquals(2, emailService.sentEmails.size, "Each start should send an email")
            }
        }
    }
}
