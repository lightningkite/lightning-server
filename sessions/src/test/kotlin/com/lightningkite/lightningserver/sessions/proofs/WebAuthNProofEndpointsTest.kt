// by Claude
package com.lightningkite.lightningserver.sessions.proofs

import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.RuntimeDeferred
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.encryption.SecretBasis
import com.lightningkite.lightningserver.encryption.signer
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.test.TestRunner
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.sessions.proofs.extensions.makeProof
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.database.*
import com.webauthn4j.converter.AttestationObjectConverter
import com.webauthn4j.converter.AuthenticatorDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.attestation.AttestationObject
import com.webauthn4j.data.attestation.authenticator.AAGUID
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionAuthenticatorOutput
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/**
 * Tests for [WebAuthNProofEndpoints].
 *
 * Registration and login data is produced by [FakeAuthenticator], which builds genuinely signed WebAuthn
 * responses using webauthn4j's own data classes, so these tests run the real verification path end to end.
 */
class WebAuthNProofEndpointsTest {

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
            override suspend fun fetch(id: Uuid): TestUser = users[id] ?: throw NotFoundException()

            override fun normalizePropertyValue(property: String, value: String): String =
                if (property == "email") value.lowercase() else value

            context(server: ServerRuntime)
            override suspend fun fetchByProperty(property: String, value: String): TestUser? = when (property) {
                "email" -> users.values.find { it.email == value }
                "TestUser/_id" -> users.values.find { it._id.toString() == value }
                else -> null
            }
        }
    }

    /** A second principal type sharing [TestUser]'s id space. */
    @Serializable
    data class TestAdmin(
        override val _id: Uuid = Uuid.random(),
    ) : HasId<Uuid> {
        companion object : PrincipalType<TestAdmin, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<TestAdmin> = serializer()

            val admins = mutableMapOf<Uuid, TestAdmin>()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): TestAdmin = admins[id] ?: throw NotFoundException()

            context(server: ServerRuntime)
            override suspend fun fetchByProperty(property: String, value: String): TestAdmin? = when (property) {
                "TestAdmin/_id" -> admins.values.find { it._id.toString() == value }
                else -> null
            }
        }
    }

    private val testBasis = SecretBasis()

    /**
     * @param residentKeyPolicy The resident-key requirement the app's `registrationForUser` returns, or null
     *   to pass the client's hint through.
     */
    private fun server(residentKeyPolicy: WebAuthN.GeneralPreference? = null) = object : ServerBuilder() {
        val database = setting("database", Database.Settings("ram"))
        val cache = setting("cache", Cache.Settings("ram"))

        init {
            register(TestUser)
            register(TestAdmin)
        }

        // A second proof method, so tests can obtain the kind of proof that unlocks credential ids in /start.
        val password = path.path("auth").path("password") include PasswordProofEndpoints(
            database = database,
            cache = cache,
            proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
        )

        val webAuthN = path.path("auth").path("webauthn") include WebAuthNProofEndpoints(
            database = database,
            cache = cache,
            proofSigner = RuntimeDeferred.Cached { testBasis.signer("proof") },
            rpId = { RP_ID },
            allowedOrigins = { setOf(ORIGIN) },
            registrationForUser = { subject, hint ->
                WebAuthN.Registration.RegistrationOptions(
                    authenticatorSelection = WebAuthN.AuthenticatorSelectionOptions(
                        residentKey = residentKeyPolicy ?: hint,
                    ),
                    user = WebAuthN.PublicKeyCredentialUserEntity(
                        displayName = "Test",
                        id = subject._id.toString(),
                        name = "test",
                    ),
                )
            },
        )
    }

    private fun newUser(email: String = "user-${Uuid.random()}@example.com"): TestUser =
        TestUser(email = email).also { TestUser.users[it._id] = it }

    @Suppress("UNCHECKED_CAST")
    context(runtime: ServerRuntime)
    private fun authFor(user: TestUser, issuedAt: kotlin.time.Instant = Clock.System.now()): Authentication<HasId<*>> =
        TestUser.testAuth(user, issuedAt = issuedAt) as Authentication<HasId<*>>

    /** A proof from the password method, as the client would hold after completing it. */
    private suspend fun passwordProofFor(user: TestUser, basis: SecretBasis = testBasis): Proof =
        basis.signer("proof").makeProof(
            info = ProofMethodInfo(via = "password", property = null, strength = 10),
            property = "TestUser/_id",
            value = user._id.toString(),
            at = Clock.System.now(),
            expireAfter = 1.hours,
        )

    context(test: TestRunner<*>)
    private suspend fun WebAuthNProofEndpoints.register(
        user: TestUser,
        authenticator: FakeAuthenticator,
        hint: WebAuthN.GeneralPreference = WebAuthN.GeneralPreference.Preferred,
        credProps: Boolean? = null,
    ) {
        val auth = authFor(user)
        val started = registerStart.test(auth, hint)
        registerFinish.test(auth, authenticator.register(started.challengeId, started.options.challenge, credProps = credProps))
    }

    context(test: TestRunner<*>)
    private suspend fun WebAuthNProofEndpoints.start(
        type: String = "TestUser",
        proof: Proof? = null,
        auth: Authentication<HasId<*>>? = null,
    ): WebAuthN.Authentication.StartResponse =
        this.start.test(auth, WebAuthN.Authentication.StartRequest(type, proof))

    context(test: TestRunner<*>)
    private suspend fun WebAuthNProofEndpoints.stored(authenticator: FakeAuthenticator): WebAuthNCredential =
        modelInfo.table().get(authenticator.id)!!

    /**
     * Asserts that [block] is rejected with exactly [message], so a test cannot pass because the request failed
     * for some other reason (for example a malformed fixture).
     */
    private inline fun assertRejected(message: String, block: () -> Unit) {
        assertEquals(message, assertFailsWith<BadRequestException>(block = block).message)
    }

    // ---------------------------------------------------------------- happy path

    @Test
    fun `register then log in without a username`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val user = newUser()
            val key = FakeAuthenticator()
            server.webAuthN.register(user, key)

            val stored = server.webAuthN.stored(key)
            assertEquals(user._id.toString(), stored.subjectId)
            assertEquals("TestUser", stored.subjectType)
            assertEquals(false, stored.backupEligible)
            assertEquals(false, stored.backupState)

            val started = server.webAuthN.start()
            val proof = server.webAuthN.prove.test(null, key.assert(started.challengeId, started.options.challenge))

            assertEquals("TestUser/_id", proof.property)
            assertEquals(user._id.toString(), proof.value)
            assertEquals(10, proof.strength)
            assertTrue(server.webAuthN.isValid(proof))
            assertEquals(1L, server.webAuthN.stored(key).lastSignCount)
        }
    }

    @Test
    fun `user verification upgrades proof strength to 20`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val key = FakeAuthenticator()
            server.webAuthN.register(newUser(), key)

            val started = server.webAuthN.start()
            val proof = server.webAuthN.prove.test(null, key.assert(started.challengeId, started.options.challenge, uv = true))

            assertEquals(20, proof.strength)
        }
    }

    // ---------------------------------------------------------------- origin and challenge binding

    @Test
    fun `registration from a disallowed origin is rejected`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val user = newUser()
            val key = FakeAuthenticator()
            val auth = authFor(user)
            val started = server.webAuthN.registerStart.test(auth, WebAuthN.GeneralPreference.Preferred)

            assertRejected("Failed to verify Authenticator") {
                server.webAuthN.registerFinish.test(
                    auth,
                    key.register(started.challengeId, started.options.challenge, origin = "https://evil.example.com")
                )
            }
            assertNull(server.webAuthN.modelInfo.table().get(key.id))
        }
    }

    @Test
    fun `login from a disallowed origin is rejected`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val key = FakeAuthenticator()
            server.webAuthN.register(newUser(), key)

            val started = server.webAuthN.start()
            assertRejected("Failed to verify Authenticator") {
                server.webAuthN.prove.test(
                    null,
                    key.assert(started.challengeId, started.options.challenge, origin = "https://evil.example.com")
                )
            }
        }
    }

    @Test
    fun `cross-origin login is rejected`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val key = FakeAuthenticator()
            server.webAuthN.register(newUser(), key)

            val started = server.webAuthN.start()
            assertRejected("Cross-origin WebAuthN requests are not permitted") {
                server.webAuthN.prove.test(
                    null,
                    key.assert(started.challengeId, started.options.challenge, crossOrigin = true)
                )
            }
        }
    }

    @Test
    fun `a challenge cannot be used twice`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val key = FakeAuthenticator()
            server.webAuthN.register(newUser(), key)

            val started = server.webAuthN.start()
            val assertion = key.assert(started.challengeId, started.options.challenge)
            server.webAuthN.prove.test(null, assertion)

            assertRejected("No Challenge available") { server.webAuthN.prove.test(null, assertion) }
        }
    }

    @Test
    fun `an assertion signed over a different challenge is rejected`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val key = FakeAuthenticator()
            server.webAuthN.register(newUser(), key)

            val first = server.webAuthN.start()
            val second = server.webAuthN.start()
            assertRejected("No Challenge available") {
                server.webAuthN.prove.test(null, key.assert(first.challengeId, second.options.challenge))
            }
        }
    }

    // ---------------------------------------------------------------- principal type scoping

    @Test
    fun `a credential cannot produce a proof for another principal type`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val user = newUser()
            // An admin with the same id, so a mislabelled proof would resolve to a real admin.
            TestAdmin.admins[user._id] = TestAdmin(user._id)
            val key = FakeAuthenticator()
            server.webAuthN.register(user, key)

            val started = server.webAuthN.start(type = "TestAdmin")
            assertRejected("Failed to verify Authenticator") {
                server.webAuthN.prove.test(null, key.assert(started.challengeId, started.options.challenge))
            }
        }
    }

    // ---------------------------------------------------------------- /start and credential id disclosure

    @Test
    fun `start without a proof discloses no credential ids`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            server.webAuthN.register(newUser(), FakeAuthenticator())

            assertEquals(emptyList(), server.webAuthN.start().options.allowCredentials)
        }
    }

    @Test
    fun `start with a proof returns that subject's credential ids`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val user = newUser()
            val key = FakeAuthenticator()
            server.webAuthN.register(user, key)
            server.webAuthN.register(newUser(), FakeAuthenticator()) // someone else's, must not appear

            val allowed = server.webAuthN.start(proof = passwordProofFor(user)).options.allowCredentials

            assertEquals(listOf(key.id), allowed.map { it.id })
            assertEquals(listOf(WebAuthN.Transport.USB), allowed.single().transports)
        }
    }

    @Test
    fun `start rejects a proof signed with the wrong key`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val user = newUser()
            server.webAuthN.register(user, FakeAuthenticator())

            assertRejected("Invalid proof") {
                server.webAuthN.start(proof = passwordProofFor(user, basis = SecretBasis()))
            }
        }
    }

    @Test
    fun `start rejects a proof that identifies no subject`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val ghost = TestUser() // never added to TestUser.users

            assertRejected("Proof does not identify a TestUser") {
                server.webAuthN.start(proof = passwordProofFor(ghost))
            }
        }
    }

    @Test
    fun `authenticated start without a proof returns the caller's own credentials`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val user = newUser()
            val key = FakeAuthenticator()
            server.webAuthN.register(user, key)

            val allowed = server.webAuthN.start(auth = authFor(user)).options.allowCredentials

            assertEquals(listOf(key.id), allowed.map { it.id })
        }
    }

    @Test
    fun `authenticated start rejects a proof for someone else`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val caller = newUser()
            val other = newUser()
            server.webAuthN.register(other, FakeAuthenticator())

            assertRejected("Proof does not match the authenticated subject") {
                server.webAuthN.start(proof = passwordProofFor(other), auth = authFor(caller))
            }
        }
    }

    @Test
    fun `login must use one of the credentials offered by start`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val alice = newUser()
            val bob = newUser()
            server.webAuthN.register(alice, FakeAuthenticator())
            val bobsKey = FakeAuthenticator()
            server.webAuthN.register(bob, bobsKey)

            val started = server.webAuthN.start(proof = passwordProofFor(alice))
            assertRejected("Failed to verify Authenticator") {
                server.webAuthN.prove.test(null, bobsKey.assert(started.challengeId, started.options.challenge))
            }
        }
    }

    // ---------------------------------------------------------------- registration

    @Test
    fun `registration rejects a credential id that differs from the attested one`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val user = newUser()
            val key = FakeAuthenticator()
            val auth = authFor(user)
            val started = server.webAuthN.registerStart.test(auth, WebAuthN.GeneralPreference.Preferred)
            val squatted = FakeAuthenticator().id

            assertRejected("Credential ID does not match the attested credential") {
                server.webAuthN.registerFinish.test(
                    auth,
                    key.register(started.challengeId, started.options.challenge, claimedId = squatted)
                )
            }
            assertNull(server.webAuthN.modelInfo.table().get(squatted))
        }
    }

    // The `.test(auth, input)` helper hands the endpoint a ready-made Access and so bypasses auth requirements,
    // which the real HTTP entry point enforces. The requirement is therefore checked directly.
    @Test
    fun `registration requires a recent sign-in`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val user = newUser()
            for (endpoint in listOf(server.webAuthN.registerStart.auth, server.webAuthN.registerFinish.auth)) {
                assertIs<AuthRequirement.Result.Accepted<*>>(endpoint.check(authFor(user)))
                assertIs<AuthRequirement.Result.Rejected>(
                    endpoint.check(authFor(user, issuedAt = Clock.System.now() - 1.hours))
                )
                assertIs<AuthRequirement.Result.Rejected>(endpoint.check(null))
            }
        }
    }

    @Test
    fun `the server decides the resident key requirement and requests credProps`(): Unit = runBlocking {
        val server = server(residentKeyPolicy = WebAuthN.GeneralPreference.Required)
        server.test({}) {
            val started = server.webAuthN.registerStart.test(authFor(newUser()), WebAuthN.GeneralPreference.Discouraged)

            assertEquals(WebAuthN.GeneralPreference.Required, started.options.authenticatorSelection.residentKey)
            assertEquals(true, started.options.extensions.credProps)
        }
    }

    @Test
    fun `residentKey prefers the credProps report, then backup eligibility`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val reported = FakeAuthenticator()
            server.webAuthN.register(newUser(), reported, credProps = true)
            assertTrue(server.webAuthN.stored(reported).residentKey)

            val synced = FakeAuthenticator(backupEligible = true)
            server.webAuthN.register(newUser(), synced)
            assertTrue(server.webAuthN.stored(synced).residentKey)

            val unknown = FakeAuthenticator()
            server.webAuthN.register(newUser(), unknown, hint = WebAuthN.GeneralPreference.Discouraged)
            assertFalse(server.webAuthN.stored(unknown).residentKey)
        }
    }

    // ---------------------------------------------------------------- stored credential state

    @Test
    fun `the owner can disable a credential but cannot rewrite its sign count`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val user = newUser()
            val key = FakeAuthenticator()
            server.webAuthN.register(user, key)
            val started = server.webAuthN.start()
            server.webAuthN.prove.test(null, key.assert(started.challengeId, started.options.challenge))
            assertEquals(1L, server.webAuthN.stored(key).lastSignCount)

            val ownersView = server.webAuthN.modelInfo.table(AuthAccess(authFor(user)))
            runCatching { ownersView.updateOneById(key.id, modification { it.lastSignCount assign 0L }) }
            assertEquals(1L, server.webAuthN.stored(key).lastSignCount)

            ownersView.updateOneById(key.id, modification { it.disabledAt assign Clock.System.now() })
            assertNotNull(server.webAuthN.stored(key).disabledAt)
        }
    }

    @Test
    fun `a disabled credential cannot log in`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val key = FakeAuthenticator()
            server.webAuthN.register(newUser(), key)
            server.webAuthN.modelInfo.table()
                .updateOneById(key.id, modification { it.disabledAt assign Clock.System.now() })

            val started = server.webAuthN.start()
            assertRejected("Failed to verify Authenticator") {
                server.webAuthN.prove.test(null, key.assert(started.challengeId, started.options.challenge))
            }
        }
    }

    @Test
    fun `a sign count that goes backwards is rejected`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val key = FakeAuthenticator()
            server.webAuthN.register(newUser(), key)

            val first = server.webAuthN.start()
            server.webAuthN.prove.test(null, key.assert(first.challengeId, first.options.challenge, signCount = 5))

            val second = server.webAuthN.start()
            assertRejected("Failed to verify Authenticator") {
                server.webAuthN.prove.test(null, key.assert(second.challengeId, second.options.challenge, signCount = 3))
            }
        }
    }

    @Test
    fun `the backup eligible flag must not change, while backup state is tracked`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val key = FakeAuthenticator(backupEligible = true)
            server.webAuthN.register(newUser(), key)
            assertEquals(true, server.webAuthN.stored(key).backupEligible)
            assertEquals(false, server.webAuthN.stored(key).backupState)

            val synced = server.webAuthN.start()
            server.webAuthN.prove.test(null, key.assert(synced.challengeId, synced.options.challenge, backupState = true))
            assertEquals(true, server.webAuthN.stored(key).backupState)

            val flipped = server.webAuthN.start()
            assertRejected("Failed to verify Authenticator") {
                server.webAuthN.prove.test(
                    null,
                    key.assert(flipped.challengeId, flipped.options.challenge, backupEligible = false)
                )
            }
        }
    }

    @Test
    fun `established reflects only active credentials of the subject's type`(): Unit = runBlocking {
        val server = server()
        server.test({}) {
            val user = newUser()
            assertFalse(server.webAuthN.established(TestUser, user))

            val key = FakeAuthenticator()
            server.webAuthN.register(user, key)
            assertTrue(server.webAuthN.established(TestUser, user))
            assertFalse(server.webAuthN.established(TestAdmin, TestAdmin(user._id)))

            server.webAuthN.modelInfo.table()
                .updateOneById(key.id, modification { it.disabledAt assign Clock.System.now() })
            assertFalse(server.webAuthN.established(TestUser, user))
        }
    }

    // ---------------------------------------------------------------- fake authenticator

    /**
     * A software authenticator holding one ES256 key, producing signed registration and login responses the
     * way a browser would hand them to the server.
     *
     * The challenge convention matches the server's: the authenticator signs over the UTF-8 bytes of the
     * challenge string from the options, and `clientDataJSON.challenge` is those bytes base64url-encoded.
     */
    private class FakeAuthenticator(
        private val backupEligible: Boolean = false,
    ) {
        private val keyPair: KeyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()
        private val credentialId = ByteArray(32).also { SecureRandom().nextBytes(it) }
        private var signCount = 0L
        private val converter = ObjectConverter()
        private val rpIdHash = sha256(RP_ID.toByteArray())

        val id: String = b64(credentialId)

        fun register(
            challengeId: String,
            challenge: String,
            origin: String = ORIGIN,
            claimedId: String = id,
            credProps: Boolean? = null,
        ): WebAuthN.Registration.RegisterRequest {
            val authenticatorData = AuthenticatorData<RegistrationExtensionAuthenticatorOutput>(
                rpIdHash,
                flags(attested = true, backupEligible = backupEligible),
                signCount,
                AttestedCredentialData(
                    AAGUID.ZERO,
                    credentialId,
                    EC2COSEKey.create(keyPair.public as ECPublicKey, COSEAlgorithmIdentifier.ES256),
                ),
            )
            val attestationObject = AttestationObjectConverter(converter)
                .convertToBytes(AttestationObject(authenticatorData, NoneAttestationStatement()))
            return WebAuthN.Registration.RegisterRequest(
                challengeId = challengeId,
                displayName = "Test Key",
                credential = WebAuthN.Registration.AttestedPublicKeyCredential(
                    authenticatorAttachment = "cross-platform",
                    clientExtensionResults = WebAuthN.Registration.CreateExtensionResponse(
                        credProps = credProps?.let { WebAuthN.Registration.CredPropsResponse(it) },
                    ),
                    id = claimedId,
                    response = WebAuthN.Registration.AuthenticatorAttestationResponse(
                        attestationObject = b64(attestationObject),
                        clientDataJSON = b64(clientData("webauthn.create", challenge, origin, crossOrigin = false)),
                        transports = listOf(WebAuthN.Transport.USB),
                    ),
                ),
            )
        }

        fun assert(
            challengeId: String,
            challenge: String,
            origin: String = ORIGIN,
            crossOrigin: Boolean = false,
            uv: Boolean = false,
            signCount: Long = this.signCount + 1,
            backupEligible: Boolean = this.backupEligible,
            backupState: Boolean = false,
        ): WebAuthN.Authentication.ProveRequest {
            this.signCount = signCount
            val authenticatorData = AuthenticatorDataConverter(converter).convert(
                AuthenticatorData<AuthenticationExtensionAuthenticatorOutput>(
                    rpIdHash,
                    flags(uv = uv, backupEligible = backupEligible, backupState = backupState),
                    signCount,
                )
            )
            val clientData = clientData("webauthn.get", challenge, origin, crossOrigin)
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(authenticatorData)
                update(sha256(clientData))
                sign()
            }
            return WebAuthN.Authentication.ProveRequest(
                challengeId = challengeId,
                credentials = WebAuthN.Authentication.AssertedPublicKeyCredential(
                    id = id,
                    clientExtensionResults = null,
                    response = WebAuthN.Authentication.AuthenticatorAssertionResponse(
                        authenticatorData = b64(authenticatorData),
                        clientDataJSON = b64(clientData),
                        signature = b64(signature),
                        userHandle = null,
                    ),
                ),
            )
        }

        private fun flags(
            uv: Boolean = false,
            attested: Boolean = false,
            backupEligible: Boolean = false,
            backupState: Boolean = false,
        ): Byte {
            var flags = AuthenticatorData.BIT_UP.toInt()
            if (uv) flags = flags or AuthenticatorData.BIT_UV.toInt()
            if (backupEligible) flags = flags or AuthenticatorData.BIT_BE.toInt()
            if (backupState) flags = flags or AuthenticatorData.BIT_BS.toInt()
            if (attested) flags = flags or AuthenticatorData.BIT_AT.toInt()
            return flags.toByte()
        }

        private fun clientData(type: String, challenge: String, origin: String, crossOrigin: Boolean): ByteArray =
            """{"type":"$type","challenge":"${b64(challenge.encodeToByteArray())}","origin":"$origin","crossOrigin":$crossOrigin}"""
                .encodeToByteArray()
    }

    companion object {
        const val RP_ID = "example.com"
        const val ORIGIN = "https://example.com"

        private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
