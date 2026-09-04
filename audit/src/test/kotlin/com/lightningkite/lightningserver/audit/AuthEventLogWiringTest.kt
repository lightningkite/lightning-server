package com.lightningkite.lightningserver.audit

import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.auth.PrincipalType
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import com.lightningkite.lightningserver.sessions.RefreshToken
import com.lightningkite.lightningserver.sessions.SessionManager
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.settings.set
import com.lightningkite.services.cache.Cache
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.database.Condition
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The auth event log's *wiring*, driven through a real rejected authentication.
 *
 * [AuthEventLogTest] calls the reporter directly, which proves the writer works and nothing at all
 * about whether anything ever calls it — the seam was shipped once with the call site passing its
 * free text into `sessionId`, and no test noticed. These go in through the refresh-token endpoint,
 * which is the flagship case for the seam existing: it is `noAuth` and rejects before any
 * authentication resolves, so no other audit layer can see the failure.
 */
class AuthEventLogWiringTest {

    @Serializable
    data class WiringUser(override val _id: Uuid) : HasId<Uuid> {
        companion object : PrincipalType<WiringUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<WiringUser> = WiringUser.serializer()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): WiringUser = WiringUser(id)
        }
    }

    class WiringSessionManager(database: Runtime<Database>) : SessionManager<WiringUser, Uuid>(
        principal = WiringUser,
        database = database,
        tokenFormat = Runtime { PrivateTinyTokenFormat() },
    ) {
        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: WiringUser): Instant? = null

        context(server: ServerRuntime)
        override suspend fun permitAuthentication(subject: WiringUser): Boolean = true

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: WiringUser): Duration? = null
    }

    /**
     * The core and the auth event log only — no disclosure or data access log — because that is also
     * the combination a deployment that only wants a login trail would include.
     */
    object TestServer : ServerBuilder() {
        val database = setting("database", Database.Settings())
        val cache = setting("cache", Cache.Settings())

        val audit = path.path("audit") include AuditCore(database)
        val authEventLog = path.path("audit-auth") include AuthEventLog(audit)

        val sessions = path.path("auth") include WiringSessionManager(database)

        init {
            registerBasicMediaTypeCoders()
        }
    }

    private fun testId(n: Int) = Uuid.parse("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))

    /** A POST to the refresh-token endpoint carrying [token] as its JSON string body. */
    private fun tokenExchange(token: String, userAgent: String? = null) = HttpRequest<PathSpec>(
        path = RawHttpEndpoint(asString = "/auth/token/simple", method = HttpMethod.POST),
        queryParameters = QueryParameters.EMPTY,
        headers = HttpHeaders {
            add(HttpHeader.ContentType, MediaType.Application.Json.toString())
            userAgent?.let { add(HttpHeader.UserAgent, it) }
        },
        domain = "example.com",
        protocol = "https",
        sourceIp = "203.0.113.7",
        body = TypedData.text("\"$token\"", MediaType.Application.Json),
    )

    private fun onServer(block: suspend context(ServerRuntime) () -> Unit) = runBlocking {
        TestServer.test(settings = { database set Database.Settings(); cache set Cache.Settings() }) {
            block(serverRuntime)
        }
    }

    context(server: ServerRuntime)
    private suspend fun events() = TestServer.authEventLog.authEvents().find(Condition.Always).toList()

    /**
     * A refresh token naming a real session but carrying the wrong secret. The most interesting
     * rejection to record, because it is the one that means someone is guessing.
     */
    context(server: ServerRuntime)
    private suspend fun tokenWithWrongSecret(subjectId: Uuid = Uuid.random()): Pair<Uuid, String> {
        val (session, _) = TestServer.sessions.newSession(subjectId)
        return session._id to RefreshToken("WiringUser", session._id, "not-the-secret").string
    }

    @Test
    fun `a rejected refresh token reaches the reporter and lands in the table`() = onServer {
        val (sessionId, token) = tokenWithWrongSecret()

        val response = serverRuntime.handle(tokenExchange(token, userAgent = "probe/1.0"), testId(1))
        assertEquals(HttpStatus.Unauthorized, response.status)

        val event = events().single()
        assertEquals(AuthEventType.AuthenticationFailed, event.type)
        assertEquals("SecretMismatch", event.failureReason)
        assertEquals(sessionId.toString(), event.sessionId)
    }

    /**
     * The account the attempt was against. `AuthEventRecord` indexes this column precisely so that
     * "when did this account start failing logins" is answerable, which is the question the layer
     * exists for — and nothing checked that the call site fills it in. Mutation testing confirmed
     * the gap: passing null for `principal` in `SessionManager.authFailed` left every test in
     * `audit`, `sessions` and `typed` green. `AuthEventLogTest` asserts the field, but it calls the
     * reporter directly, so it covers the writer and not the caller — the same split this file's
     * header describes for `sessionId`.
     */
    @Test
    fun `the event names the account the attempt was against`() = onServer {
        val subjectId = Uuid.parse("00000000-0000-4000-8000-00000000beef")
        val (_, token) = tokenWithWrongSecret(subjectId)

        serverRuntime.handle(tokenExchange(token, userAgent = "probe/1.0"), testId(7))

        assertEquals(
            subjectId.toString(),
            events().single().principal,
            "without the principal, the log cannot answer which account was being attacked",
        )
    }

    /** Where the attempt came from, as observed on the request that made it. */
    @Test
    fun `the observed source ip and user agent are recorded`() = onServer {
        val (_, token) = tokenWithWrongSecret()

        serverRuntime.handle(tokenExchange(token, userAgent = "probe/1.0"), testId(2))

        val event = events().single()
        assertEquals("203.0.113.7", event.sourceIp)
        assertEquals("probe/1.0", event.userAgent)
    }

    /**
     * Absent is absent. A blank user agent in this column would read as a genuine observation to
     * whoever queries the log, which is the mistake the session row's `userAgents` set already made
     * once.
     */
    @Test
    fun `an unobserved user agent is null rather than blank`() = onServer {
        val (_, token) = tokenWithWrongSecret()

        serverRuntime.handle(tokenExchange(token, userAgent = null), testId(3))

        val event = events().single()
        assertNull(event.userAgent, "an unsent user agent must not be recorded as an observed one")
        assertEquals("203.0.113.7", event.sourceIp)
    }

    /** A well-formed token naming a session that never existed. Costs an attacker nothing to make. */
    private fun forgedToken() = RefreshToken("WiringUser", Uuid.random(), "anything").string

    /**
     * A refresh token is not signed — [RefreshToken.valid] is a prefix check and the session id is
     * read back out of the attacker's own string — so anyone can produce a `NoSuchSession` rejection
     * on demand. Recording it would mean an unauthenticated caller writes one audit row per request,
     * into the same database the fail-closed disclosure and data access logs depend on.
     *
     * The rejection itself must not change: the request still fails, it just is not evidence.
     */
    @Test
    fun `a forged token naming no real session is rejected without recording an event`() = onServer {
        val response = serverRuntime.handle(tokenExchange(forgedToken(), userAgent = "probe/1.0"), testId(5))

        assertEquals(HttpStatus.Unauthorized, response.status)
        assertTrue(
            events().isEmpty(),
            "a rejection anyone can forge was recorded as an authentication event, which lets an " +
                "unauthenticated caller grow the audit table one row at a time",
        )
    }

    /**
     * The amplification property itself, rather than one instance of it. This is the assertion that
     * would have failed before `reachableWithoutCredentials` existed: the guard used to be a
     * two-value list inside `authFailed`, and `NoSuchSession` was added to the enum afterwards
     * without being added to the list.
     */
    @Test
    fun `spraying forged tokens cannot grow the audit table`() = onServer {
        repeat(25) { serverRuntime.handle(tokenExchange(forgedToken()), testId(100 + it)) }

        assertEquals(0, events().size, "forged tokens wrote audit rows")
    }

    /**
     * The other half of the trade: the attempt is not invisible, it is just recorded as a request
     * rather than as an authentication event. Without this the mitigation above would be dropping
     * the evidence rather than relocating it.
     */
    @Test
    fun `a forged attempt is still visible in the request log`() = onServer {
        serverRuntime.handle(tokenExchange(forgedToken()), testId(6))

        val requests = TestServer.audit.requests().find(Condition.Always).toList()
        assertTrue(
            requests.any { it._id == testId(6) },
            "a forged attempt left no trace at all — it must still appear as a request",
        )
    }

    /**
     * The event has to join the request record for the attempt, or an auditor cannot put the failure
     * next to anything else that happened on that request.
     */
    @Test
    fun `the event joins the request record of the attempt`() = onServer {
        val (_, token) = tokenWithWrongSecret()

        serverRuntime.handle(tokenExchange(token, userAgent = "probe/1.0"), testId(4))

        assertEquals(testId(4), events().single().requestId)
        val requests = TestServer.audit.requests().find(Condition.Always).toList()
        assertTrue(
            requests.any { it._id == testId(4) },
            "the auth event points at a request record that was never written",
        )
    }
}
