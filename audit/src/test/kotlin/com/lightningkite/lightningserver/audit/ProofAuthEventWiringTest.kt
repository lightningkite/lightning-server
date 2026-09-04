package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.auth.noAuth
import com.lightningkite.lightningserver.typed.ApiHttpHandler
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.auth.idString
import com.lightningkite.lightningserver.auth.register
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.sessions.*
import com.lightningkite.lightningserver.sessions.proofs.*
import com.lightningkite.lightningserver.sessions.proofs.extensions.code
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.insertOne
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The proof endpoints' auth-event wiring, driven through real HTTP requests.
 *
 * Same reasoning as [AuthEventLogWiringTest]: [AuthEventLogTest] proves the writer works and nothing
 * about whether anything calls it. These go in through `prove`, which is where credential *guessing*
 * happens — `SessionManager.authFailed` only ever saw refresh-token rejections, which are mostly
 * expired sessions.
 *
 * ## What this file deliberately does not assume
 * Proof *issuance* and proof *acceptance* are different events. `Signer.makeProof` is reached both by
 * a `prove` handler that has just checked a secret and by `PinBasedProofEndpoints.issueProof`, which
 * mints a proof to be mailed to somebody (a magic link) with nothing presented at all. Only the first
 * is an acceptance; `a mailed magic link is recorded as issued, not as accepted` pins that down.
 */
class ProofAuthEventWiringTest {

    @Serializable
    data class ProofUser(
        override val _id: Uuid = Uuid.random(),
        val email: String = "",
    ) : HasId<Uuid> {
        companion object : PrincipalType<ProofUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<ProofUser> = serializer()

            val users: MutableMap<Uuid, ProofUser> = mutableMapOf()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): ProofUser = users[id] ?: ProofUser(id)

            override fun normalizePropertyValue(property: String, value: String): String =
                if (property == "email") value.lowercase().trim() else value

            context(server: ServerRuntime)
            override suspend fun fetchByProperty(property: String, value: String): ProofUser? = when (property) {
                "email" -> users.values.find { it.email == value }
                "ProofUser/_id" -> users.values.find { it._id.toString() == value }
                else -> super.fetchByProperty(property, value)
            }
        }
    }

    /** A PIN method with delivery replaced by a capture, so the code is available to the test. */
    class CapturingPinEndpoints(pin: PinHandler) : PinBasedProofEndpoints(
        name = "testpin",
        property = "email",
        proofExpiration = 1.hours,
        pin = pin,
        exampleTarget = "test@test.com",
    ) {
        val sent: MutableList<Pair<String, String>> = mutableListOf()

        context(_: ServerRuntime)
        override suspend fun send(to: String, pin: String) {
            sent += to to pin
        }

        /** Reaches the magic-link mint, which is `protected` on the base class. */
        context(_: ServerRuntime)
        suspend fun mintMagicLink(destination: String): Proof = issueProof(destination)

        /**
         * Stands in for an application's own "email me a login link" route.
         *
         * The framework ships no such endpoint — magic links are always the application calling
         * `send(destination) { ... }` from a route of its own — so the realistic case has to be
         * modelled here. It passes `request`, which is the whole point: that is where the IP and
         * user agent of whoever asked for the link come from.
         */
        val sendLink: ApiHttpHandler<PathSpec0, HasId<*>?, String, Boolean> =
            path.path("send-link").post bind ApiHttpHandler(
                auth = noAuth,
                summary = "Send a magic link",
                description = "Mints a proof to be mailed to the address, presenting nothing.",
                errorCases = emptyList(),
                successCode = HttpStatus.OK,
                implementation = { destination: String ->
                    issueProof(destination, request)
                    true
                },
            )
    }

    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())

        val audit = path.path("audit") include AuditCore(database)
        val authEventLog = path.path("audit-auth") include AuthEventLog(audit)

        val password = path.path("password") include PasswordProofEndpoints(database, cache)
        val totp = path.path("totp") include TimeBasedOTPProofEndpoints(database, cache)
        val backupCode = path.path("backup") include BackupCodeEndpoints(database, cache)
        val knownDevice = path.path("device") include KnownDeviceProofEndpoints(database, cache)
        val pin = path.path("pin") include CapturingPinEndpoints(PinHandler(cache, "testpin"))
        val webAuthN = path.path("webauthn") include WebAuthNProofEndpoints(
            database = database,
            cache = cache,
            rpId = { "example.com" },
            registrationForUser = { _, _ ->
                WebAuthN.Registration.RegistrationOptions(
                    user = WebAuthN.PublicKeyCredentialUserEntity("Test", "id", "test")
                )
            },
        )

        init {
            register(ProofUser)
            registerBasicMediaTypeCoders()
        }
    }

    private val json = Json { encodeDefaults = true }

    private fun testId(n: Int) = Uuid.parse("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))

    private fun post(
        path: String,
        body: String,
        userAgent: String? = "probe/1.0",
        sourceIp: String = "203.0.113.7",
    ) = HttpRequest<PathSpec>(
        path = RawHttpEndpoint(asString = path, method = HttpMethod.POST),
        queryParameters = QueryParameters.EMPTY,
        headers = HttpHeaders {
            add(HttpHeader.ContentType, MediaType.Application.Json.toString())
            userAgent?.let { add(HttpHeader.UserAgent, it) }
        },
        domain = "example.com",
        protocol = "https",
        sourceIp = sourceIp,
        body = TypedData.text(body, MediaType.Application.Json),
    )

    private fun identify(property: String, value: String, secret: String) = json.encodeToString(
        IdentificationAndPassword.serializer(),
        IdentificationAndPassword(type = "ProofUser", property = property, value = value, password = secret),
    )

    private fun onServer(block: suspend context(ServerRuntime) () -> Unit) = runBlocking {
        ProofUser.users.clear()
        TestServer.test(settings = { database set Database.Settings(); cache set Cache.Settings() }) {
            block(serverRuntime)
        }
    }

    context(server: ServerRuntime)
    private suspend fun events() = TestServer.authEventLog.authEvents().find(Condition.Always).toList()

    private fun user(email: String = "victim@example.com"): ProofUser =
        ProofUser(Uuid.random(), email).also { ProofUser.users[it._id] = it }

    // ---- password ----------------------------------------------------------------------------

    /**
     * The flagship case. A wrong password against a real account is the event the whole layer exists
     * for, and before this it was recorded nowhere at all: `prove` is `noAuth` and throws, so no
     * other audit layer sees it either.
     */
    @Test
    fun `a rejected password names the account and the method`() = onServer {
        val victim = user()
        TestServer.password.establish(ProofUser, victim._id, EstablishPassword("correct-horse"))

        val response = serverRuntime.handle(
            post("/password/prove", identify("email", victim.email, "wrong-password")),
            testId(1),
        )
        assertEquals(HttpStatus.BadRequest, response.status)

        val event = events().single()
        assertEquals(AuthEventType.ProofRejected, event.type)
        assertEquals(victim._id.toString(), event.principal, "the log must say which account was attacked")
        assertEquals("SecretMismatch", event.failureReason)
        assertEquals("password", event.method)
    }

    /** Where the attempt came from, as observed on the request that carried it. */
    @Test
    fun `the observed source ip and user agent are recorded`() = onServer {
        val victim = user()
        TestServer.password.establish(ProofUser, victim._id, EstablishPassword("correct-horse"))

        serverRuntime.handle(post("/password/prove", identify("email", victim.email, "nope")), testId(2))

        val event = events().single()
        assertEquals("203.0.113.7", event.sourceIp)
        assertEquals("probe/1.0", event.userAgent)
    }

    /** Absent is absent: a blank user agent would read as a genuine observation. */
    @Test
    fun `an unobserved user agent is null rather than blank`() = onServer {
        val victim = user()
        TestServer.password.establish(ProofUser, victim._id, EstablishPassword("correct-horse"))

        serverRuntime.handle(
            post("/password/prove", identify("email", victim.email, "nope"), userAgent = null),
            testId(3),
        )

        val event = events().single()
        assertNull(event.userAgent, "an unsent user agent must not be recorded as an observed one")
        assertEquals("203.0.113.7", event.sourceIp)
    }

    /**
     * A guess at an account that does not exist. Recorded — a run of these against different
     * addresses is what enumeration looks like — but with no principal, because none resolved and
     * inventing one would make the row assert something that did not happen.
     */
    @Test
    fun `a failure against no account is still recorded, with no principal`() = onServer {
        val response = serverRuntime.handle(
            post("/password/prove", identify("email", "nobody@example.com", "guess")),
            testId(4),
        )
        assertEquals(HttpStatus.BadRequest, response.status)

        val event = events().single()
        assertEquals(AuthEventType.ProofRejected, event.type)
        assertEquals("NoSuchSubject", event.failureReason)
        assertNull(event.principal, "no account resolved, so there is no principal to name")
        assertEquals("password", event.method)
    }

    /** The other half: an accepted credential is an event too, and the one a rejection is counted against. */
    @Test
    fun `an accepted password is recorded`() = onServer {
        val victim = user()
        TestServer.password.establish(ProofUser, victim._id, EstablishPassword("correct-horse"))

        val response = serverRuntime.handle(
            post("/password/prove", identify("email", victim.email, "correct-horse")),
            testId(5),
        )
        assertEquals(HttpStatus.OK, response.status)

        val event = events().single()
        assertEquals(AuthEventType.ProofAccepted, event.type)
        assertEquals(victim._id.toString(), event.principal)
        assertEquals("password", event.method)
        assertNull(event.failureReason)
    }

    /**
     * Once the limiter starts answering, the guesses stop reaching the credential check — and without
     * this, stop leaving any trace, so "is this still going on?" becomes unanswerable at exactly the
     * point it matters.
     */
    @Test
    fun `attempts that continue past the rate limit are recorded`() = onServer {
        val victim = user()
        TestServer.password.establish(ProofUser, victim._id, EstablishPassword("correct-horse"))

        repeat(8) {
            serverRuntime.handle(post("/password/prove", identify("email", victim.email, "nope")), testId(10 + it))
        }

        assertTrue(
            events().any { it.failureReason == "RateLimited" },
            "a blocked attempt left no record, so a continuing attack looks like it stopped",
        )
    }

    // ---- TOTP --------------------------------------------------------------------------------

    context(server: ServerRuntime)
    private suspend fun totpFor(victim: ProofUser): TotpSecret = TotpSecret(
        subjectId = ProofUser.idString(victim._id),
        subjectType = ProofUser.name,
        secretBase32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
        label = "test",
        issuer = "TestApp",
        period = 30.seconds,
        digits = 6,
        algorithm = TotpHashAlgorithm.SHA1,
        establishedAt = Clock.System.now(),
        lastUsedAt = Clock.System.now(),
    ).also { TestServer.totp.modelInfo.table().insertOne(it) }

    @Test
    fun `a rejected TOTP code names the account and the method`() = onServer {
        val victim = user()
        totpFor(victim)

        serverRuntime.handle(
            post("/totp/prove", identify("ProofUser/_id", victim._id.toString(), "000000")),
            testId(20),
        )

        val event = events().single()
        assertEquals(AuthEventType.ProofRejected, event.type)
        assertEquals(victim._id.toString(), event.principal)
        assertEquals("SecretMismatch", event.failureReason)
        assertEquals("totp", event.method)
    }

    /** A replayed code is a different event from a wrong one: it means the code leaked, not that it was guessed. */
    @Test
    fun `a replayed TOTP code is recorded as a reuse`() = onServer {
        val victim = user()
        val secret = totpFor(victim)
        val body = identify("ProofUser/_id", victim._id.toString(), secret.code)

        serverRuntime.handle(post("/totp/prove", body), testId(21))
        serverRuntime.handle(post("/totp/prove", body), testId(22))

        val all = events()
        assertEquals(1, all.count { it.type == AuthEventType.ProofAccepted })
        val rejected = all.single { it.type == AuthEventType.ProofRejected }
        assertEquals("SecretAlreadyUsed", rejected.failureReason)
        assertEquals(victim._id.toString(), rejected.principal)
    }

    // ---- backup codes ------------------------------------------------------------------------

    @Test
    fun `a rejected backup code names the account and the method`() = onServer {
        val victim = user()
        TestServer.backupCode.modelInfo.table().insertOne(
            BackupCodeSecret(
                code = "abcdefghij",
                subjectId = ProofUser.idString(victim._id),
                subjectType = ProofUser.name,
                createdAt = Clock.System.now(),
            )
        )

        serverRuntime.handle(
            post("/backup/prove", identify("email", victim.email, "zzzzzzzzzz")),
            testId(30),
        )

        val event = events().single()
        assertEquals(AuthEventType.ProofRejected, event.type)
        assertEquals(victim._id.toString(), event.principal)
        assertEquals("SecretMismatch", event.failureReason)
        assertEquals("backupcode", event.method)
    }

    // ---- known device ------------------------------------------------------------------------

    @Test
    fun `a rejected known-device secret names the account and the method`() = onServer {
        val victim = user()
        val established = TestServer.knownDevice.establish(ProofUser, victim._id, "test device")
        val deviceId = established.secret.substringBefore('/')

        serverRuntime.handle(
            post("/device/prove", "\"$deviceId/not-the-secret\""),
            testId(40),
        )

        val event = events().single()
        assertEquals(AuthEventType.ProofRejected, event.type)
        assertEquals(victim._id.toString(), event.principal)
        assertEquals("SecretMismatch", event.failureReason)
        assertEquals("known-device", event.method)
    }

    // ---- emailed / texted PIN ----------------------------------------------------------------

    /** Runs the real `start` endpoint and returns the key it issued alongside the captured PIN. */
    context(server: ServerRuntime)
    private suspend fun startPin(email: String): Pair<String, String> {
        val response = serverRuntime.handle(post("/pin/start", "\"$email\""), testId(50))
        val key = json.decodeFromString(String.serializer(), response.body!!.text())
        return key to TestServer.pin.sent.last().second
    }

    private fun finish(key: String, code: String) =
        json.encodeToString(FinishProof.serializer(), FinishProof(key = key, password = code))

    /**
     * A PIN proves ownership of an address, not of an account — these endpoints never resolve a
     * subject, that happens later at login — so the address is what the event is about and the only
     * identity a failure has.
     */
    @Test
    fun `a rejected PIN names the address it was against`() = onServer {
        TestServer.pin.sent.clear()
        val (key, _) = startPin("victim@example.com")

        serverRuntime.handle(post("/pin/prove", finish(key, "ZZZZZZ")), testId(51))

        val event = events().single()
        assertEquals(AuthEventType.ProofRejected, event.type)
        assertEquals("victim@example.com", event.principal)
        assertEquals("SecretMismatch", event.failureReason)
        assertEquals("testpin", event.method)
        assertEquals("email", event.methodProperty)
    }

    /** An unknown or expired key names nothing: there is no pending attempt behind it to attribute. */
    @Test
    fun `a PIN attempt against an unknown key is recorded with no principal`() = onServer {
        serverRuntime.handle(post("/pin/prove", finish(Uuid.random().toString(), "ZZZZZZ")), testId(52))

        val event = events().single()
        assertEquals(AuthEventType.ProofRejected, event.type)
        assertEquals("SecretExpired", event.failureReason)
        assertNull(event.principal)
        assertEquals("testpin", event.method)
    }

    @Test
    fun `an accepted PIN is recorded`() = onServer {
        TestServer.pin.sent.clear()
        val (key, code) = startPin("victim@example.com")

        val response = serverRuntime.handle(post("/pin/prove", finish(key, code)), testId(53))
        assertEquals(HttpStatus.OK, response.status)

        val event = events().single()
        assertEquals(AuthEventType.ProofAccepted, event.type)
        assertEquals("victim@example.com", event.principal)
        assertEquals("testpin", event.method)
    }

    /**
     * The issuance/acceptance split, asserted rather than assumed. `issueProof` mints a proof to be
     * mailed to an address — a magic link — with no credential presented by anyone. Recording that as
     * `ProofAccepted` would put a login in the log that never happened, and would make "how many
     * times did this account authenticate" wrong.
     *
     * It *is* recorded, as [AuthEventType.ProofIssued]. The link is a bearer credential from the
     * moment it exists, so an attacker who can cause one to be issued to an address they control
     * needs no credential at all — and the resulting login looks entirely ordinary. The issuance is
     * the only point at which that shows up.
     */
    @Test
    fun `a mailed magic link is recorded as issued, not as accepted`() = onServer {
        TestServer.pin.mintMagicLink("victim@example.com")

        val event = events().single()
        assertEquals(
            AuthEventType.ProofIssued,
            event.type,
            "minting a proof to mail to someone must not read as a presented credential being accepted",
        )
        assertEquals("victim@example.com", event.principal)
        assertEquals("testpin", event.method)
    }

    /**
     * The trace this event exists for, end to end: a frontend asks for a link to be mailed, and the
     * log can name who asked.
     *
     * A magic link is a bearer credential, so "someone caused a link to be sent to an address they
     * control" is the attack, and it is invisible at login time — the resulting authentication looks
     * entirely ordinary. The issuance is the only point where the requester's origin is knowable, so
     * an issuance event with no origin would record that something happened while losing the one
     * fact worth having.
     */
    @Test
    fun `an issuance names the origin of the request that asked for it`() = onServer {
        serverRuntime.handle(post("/pin/send-link", "\"victim@example.com\""), testId(20))

        val event = events().single()
        assertEquals(AuthEventType.ProofIssued, event.type)
        assertEquals("victim@example.com", event.principal)
        assertEquals("203.0.113.7", event.sourceIp, "the log must name who asked for the link")
        assertEquals("probe/1.0", event.userAgent)
    }

    /** And the same origin is reachable through the request log, independently of the event's own columns. */
    @Test
    fun `an issuance joins the request record of the call that asked for it`() = onServer {
        serverRuntime.handle(post("/pin/send-link", "\"victim@example.com\""), testId(21))

        val event = events().single()
        assertEquals(testId(21), event.requestId)
        val requests = TestServer.audit.requests().find(Condition.Always).toList()
        val row = requests.singleOrNull { it._id == testId(21) }
        assertEquals("203.0.113.7", row?.sourceIp, "the issuance points at no request record")
    }

    /**
     * Where issuance genuinely has no request behind it — a scheduled re-invite, say — the origin
     * stays absent rather than becoming a placeholder. A fabricated IP reads as a real observation
     * to whoever queries the log.
     */
    @Test
    fun `an issuance with no request records no origin rather than a fake one`() = onServer {
        TestServer.pin.mintMagicLink("victim@example.com")

        val event = events().single()
        assertEquals(AuthEventType.ProofIssued, event.type)
        assertNull(event.sourceIp, "a request-less issuance must not be given a fabricated origin")
        assertNull(event.userAgent, "a request-less issuance must not be given a fabricated user agent")
    }

    // ---- WebAuthN ----------------------------------------------------------------------------

    /**
     * The challenge is single-use and time-limited, so an unknown id is one that expired, was already
     * spent, or was never issued. No subject is known at that point — the credential has not been
     * looked at yet — so the row names none.
     */
    @Test
    fun `a WebAuthN attempt with no live challenge is recorded`() = onServer {
        val clientData = Base64.encode(
            """{"challenge":"YWJj","origin":"https://example.com","crossOrigin":false,"type":"webauthn.get"}"""
                .encodeToByteArray()
        )
        val body = json.encodeToString(
            WebAuthN.Authentication.ProveRequest.serializer(),
            WebAuthN.Authentication.ProveRequest(
                challengeId = Uuid.random().toString(),
                credentials = WebAuthN.Authentication.AssertedPublicKeyCredential(
                    id = "aWQ",
                    clientExtensionResults = null,
                    response = WebAuthN.Authentication.AuthenticatorAssertionResponse(
                        authenticatorData = "YQ",
                        clientDataJSON = clientData,
                        signature = "Yg",
                        userHandle = null,
                    ),
                ),
            ),
        )

        serverRuntime.handle(post("/webauthn/prove", body), testId(60))

        val event = events().single()
        assertEquals(AuthEventType.ProofRejected, event.type)
        assertEquals("SecretExpired", event.failureReason)
        assertNull(event.principal)
        assertEquals("WebAuthN", event.method)
    }
}
