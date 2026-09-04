// by Claude
package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.handle
import com.lightningkite.lightningserver.runtime.serverRuntime
import com.lightningkite.lightningserver.runtime.test.test
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.HasId
import com.lightningkite.services.database.get
import com.lightningkite.services.database.Table
import com.lightningkite.services.database.Condition
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.services.database.postCreate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.junit.Test
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Tests for SessionManager - session-based authentication.
 */
class SessionManagerTest {

    @Serializable
    data class SessionTestUser(
        override val _id: Uuid = Uuid.random(),
        val email: String = "",
        val active: Boolean = true,
    ) : HasId<Uuid> {
        companion object : PrincipalType<SessionTestUser, Uuid> {
            override val idSerializer: KSerializer<Uuid> = Uuid.serializer()
            override val subjectSerializer: KSerializer<SessionTestUser> = serializer()

            val users = mutableMapOf<Uuid, SessionTestUser>()

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): SessionTestUser = users[id] ?: SessionTestUser(id)
        }
    }

    class TestSessionManager(
        database: Runtime<Database>,
        private val expirationDuration: Duration? = 30.days,
        private val staleDuration: Duration? = 7.days,
        sessionSignals: context(ServerRuntime) (Table<Session<SessionTestUser, Uuid>>) -> Table<Session<SessionTestUser, Uuid>> = { it },
    ) : SessionManager<SessionTestUser, Uuid>(
        principal = SessionTestUser,
        database = database,
        tokenFormat = Runtime { PrivateTinyTokenFormat() },
        sessionSignals = sessionSignals,
    ) {
        context(server: ServerRuntime)
        override suspend fun sessionExpiration(subject: SessionTestUser): Instant? =
            expirationDuration?.let { com.lightningkite.lightningserver.runtime.now() + it }

        context(server: ServerRuntime)
        override suspend fun permitAuthentication(subject: SessionTestUser): Boolean = subject.active

        context(server: ServerRuntime)
        override suspend fun sessionStaleAfter(subject: SessionTestUser): Duration? = staleDuration
    }

    @Test
    fun `newSession creates a session and returns refresh token`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create a new session
                val (session, refreshToken) = server.sessions.newSession(userId)

                // Verify session properties
                assertEquals(userId, session.subjectId)
                assertNotNull(session._id)
                assertNotNull(session.secretHash)
                assertNotNull(session.createdAt)
                assertNotNull(session.lastUsed)
                assertEquals(setOf(GrantedScope.root), session.scopes)

                // Verify refresh token properties
                assertNotNull(refreshToken.string)
                assertEquals("SessionTestUser", refreshToken.type)
                assertEquals(session._id, refreshToken._id)
            }
        }
    }

    @Test
    fun `access-log interceptor names the authenticated principal`() = runBlocking {
        // v4-style access log via an opt-in interceptor: it resolves the request's Authentication (whose
        // toString also renders masquerade, covered by AuthenticationExtTest) and logs it. We capture the
        // emitted line to prove the principal, not just the IP, is recorded.
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            init { install(AccessLogInterceptor()) }

            val sessions = path.path("auth") include TestSessionManager(database = database)
            val ping = path.path("ping").get bind HttpHandler<PathSpec0> { HttpResponse.plainText("pong") }
        }.let { server ->
            server.test({}) {
                val (_, refreshToken) = server.sessions.newSession(userId)
                val accessToken = server.sessions.tokenSimple.test(null, refreshToken.string)

                // Attach the log capture after settings are applied, so the framework's logback setup can't
                // wipe it. Scoped to this logger and detached in finally.
                val logbackLogger = LoggerFactory.getLogger("com.lightningkite.lightningserver") as Logger
                val appender = ListAppender<ILoggingEvent>().apply { start() }
                logbackLogger.level = Level.INFO
                logbackLogger.addAppender(appender)
                try {
                    runBlocking {
                        serverRuntime.handle(
                            HttpRequest<PathSpec>(
                                path = RawHttpEndpoint(asString = "/ping", method = HttpMethod.GET),
                                queryParameters = QueryParameters.EMPTY,
                                headers = HttpHeaders { add(HttpHeader.Authorization, "Bearer $accessToken") },
                                domain = "example.com",
                                protocol = "https",
                                sourceIp = "local",
                            ),
                            generateRequestId(),
                        )
                    }
                } finally {
                    logbackLogger.detachAppender(appender)
                }

                val accessLine = appender.list.map { it.formattedMessage }.singleOrNull { it.contains("accessed by") }
                    ?: fail("Expected an access-log line; got: ${appender.list.map { it.formattedMessage }}")
                assertTrue(
                    accessLine.contains("SessionTestUser") && accessLine.contains(userId.toString()),
                    "access log should name the authenticated principal; was: $accessLine",
                )
            }
        }
    }

    @Test
    fun `tokenSimple exchanges refresh token for access token`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create a new session
                val (session, refreshToken) = server.sessions.newSession(userId)

                // Exchange refresh token for access token
                val accessToken = server.sessions.tokenSimple.test(null, refreshToken.string)

                assertNotNull(accessToken)
                assertTrue(accessToken.isNotEmpty())
            }
        }
    }

    /**
     * The session's `ips` and `userAgents` sets are the closest thing this system has to an
     * authentication audit trail, so an unobserved value must be absent from them rather than
     * present as a placeholder — an empty-string user agent reads as a real observation to anyone
     * querying it, exactly as the literal "test" ip did on the request-less path.
     */
    @Test
    fun `an unobserved user agent is not recorded as a placeholder`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (session, refreshToken) = server.sessions.newSession(userId)
                // The harness supplies a source ip but no User-Agent header.
                server.sessions.tokenSimple.test(null, refreshToken.string)

                val stored = with(serverRuntime) { server.sessions.sessionInfo.table().get(session._id) }
                assertNotNull(stored)
                assertEquals(
                    emptySet(),
                    stored.userAgents,
                    "an absent user agent was recorded as a value",
                )
                // The ip was genuinely observed, so it is genuinely recorded.
                assertEquals(setOf("localhost"), stored.ips)
            }
        }
    }

    /**
     * The other half of the same audit-trail promise: what *is* observed must be the request's own
     * value, not something the code supplies. The test above drives the session through the typed
     * harness, which fixes the ip and sends no User-Agent, so it can only show that an absent value
     * stays absent. Here a real request presents its refresh token as a bearer credential — the path
     * a client that has not yet exchanged for an access token actually takes — carrying an ip and a
     * user agent that appear nowhere else, so a recorded constant could not pass for either.
     */
    @Test
    fun `a session use records the ip and user agent the request carried`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (session, refreshToken) = server.sessions.newSession(userId)

                val request = HttpRequest<PathSpec>(
                    path = RawHttpEndpoint(asString = "/ping", method = HttpMethod.GET),
                    queryParameters = QueryParameters.EMPTY,
                    headers = HttpHeaders {
                        add(HttpHeader.Authorization, "Bearer ${refreshToken.string}")
                        add(HttpHeader.UserAgent, "ExampleClient/9.9 (audit-trail probe)")
                    },
                    domain = "example.com",
                    protocol = "https",
                    sourceIp = "203.0.113.7",
                )
                // Resolving the request's authentication is what drives the session-use update. If the
                // token were not accepted no update would happen at all, and the assertions below would
                // be reading an untouched session rather than a recorded one.
                val resolved = with(serverRuntime) { request[Authentication.CacheKey] }
                assertNotNull(resolved, "the refresh token was not accepted, so no session use was recorded")

                val stored = with(serverRuntime) { server.sessions.sessionInfo.table().get(session._id) }
                assertNotNull(stored)
                assertEquals(setOf("203.0.113.7"), stored.ips, "the recorded ip is not the one the request came from")
                assertEquals(
                    setOf("ExampleClient/9.9 (audit-trail probe)"),
                    stored.userAgents,
                    "the recorded user agent is not the one the request sent",
                )
            }
        }
    }

    /**
     * The auth event log needs to observe session creation and termination, and the write paths that
     * do both are sealed — `newSession` is not open, `terminateSessionById` is private. The signals
     * seam is what makes them observable from outside without unsealing anything.
     */
    @Test
    fun `the signals seam sees session writes`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")

        val created = mutableListOf<Uuid>()

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(
                database = database,
                sessionSignals = { table -> table.postCreate { created += it._id } },
            )
        }.let { server ->
            server.test({}) {
                val (session, _) = server.sessions.newSession(userId)
                assertEquals(listOf(session._id), created, "the seam did not observe session creation")
            }
        }
    }

    /**
     * A session row is the only record that a session ever existed, and this file twice promises it
     * is "kept for audit trail" — but delete was granted to super-users and exposed over REST, so
     * the evidence could be erased with nothing recording that it had been. Termination is the
     * supported operation and is unaffected: it goes through table(), which does not consult these
     * permissions.
     */
    @Test
    fun `session rows cannot be deleted, even by a super user`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)

            init {
                // Make the test subject a super user, so `isRoot` resolves to Always. Without this
                // every branch collapses to Never for an unprivileged caller and the assertion below
                // would hold no matter what delete was granted.
                AuthRequirement.isSuperUser = SessionTestUser.require()
            }
        }.let { server ->
            server.test({}) {
                val (session, _) = server.sessions.newSession(userId)
                val auth = with(server.sessions) { session.toAuth() }
                val permissions = with(serverRuntime) {
                    server.sessions.sessionInfo.permissions(AuthAccess(auth))
                }
                assertTrue(
                    permissions.delete == Condition.Never,
                    "a super user can delete session rows, erasing the only record the session existed",
                )
            }
        }
    }

    @Test
    fun `tokenSimple rejects invalid refresh token`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Try with invalid refresh token
                assertFailsWith<Exception>("Invalid refresh token should be rejected") {
                    server.sessions.tokenSimple.test(null, "invalid_token_string")
                }
            }
        }
    }

    @Test
    /**
     * `permitAuthentication` returning false is what refuses this, not an [AuthRequirement].
     *
     * `tokenSimple` is `noAuth` — it has to be, since exchanging a refresh token is how a caller
     * becomes authenticated in the first place — so nothing about the endpoint's requirement can
     * reject anyone. The assertion below pins that, so a future change that moved the rejection into
     * a requirement would fail here rather than quietly keep the test green under a different
     * mechanism than its name describes.
     */
    fun `tokenSimple rejects a user permitAuthentication turned away`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (_, refreshToken) = server.sessions.newSession(userId)

                SessionTestUser.users[userId] = user.copy(active = false)
                // Nothing here can be an authorization failure: the endpoint demands no authentication.
                assertEquals(
                    noAuth,
                    server.sessions.tokenSimple.auth,
                    "tokenSimple gained an auth requirement, so the rejection below no longer proves " +
                        "what this test claims — it could now be the requirement rather than permitAuthentication",
                )
                assertFailsWith<ForbiddenException>("an inactive user's refresh token must be refused") {
                    server.sessions.tokenSimple.test(null, refreshToken.string)
                }
            }
        }
    }

    @Test
    fun `session toAuth converts session to authentication`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create a new session
                val (session, _) = server.sessions.newSession(userId)

                // Convert to authentication
                val auth = with(server.sessions) { session.toAuth() }

                assertEquals("SessionTestUser", auth.principalName)
                assertEquals(userId, auth.id)
                assertEquals(session._id.toString(), auth.sessionId)
                assertEquals(session.scopes, auth.scopes)
            }
        }
    }

    @Test
    fun `newSession with custom scopes limits permissions`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val limitedScopes = setOf(GrantedScope("api:read"))

                // Create a session with limited scopes
                val (session, _) = server.sessions.newSession(
                    subjectId = userId,
                    scopes = limitedScopes
                )

                assertEquals(limitedScopes, session.scopes)
            }
        }
    }

    @Test
    fun `newSession with label stores the label`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (session, _) = server.sessions.newSession(
                    subjectId = userId,
                    label = "Mobile App"
                )

                assertEquals("Mobile App", session.label)
            }
        }
    }

    @Test
    fun `newSession with derivedFrom links to parent session`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create parent session
                val (parentSession, _) = server.sessions.newSession(userId)

                // Create derived session
                val (childSession, _) = server.sessions.newSession(
                    subjectId = userId,
                    derivedFrom = parentSession._id
                )

                assertEquals(parentSession._id, childSession.derivedFrom)
            }
        }
    }

    @Test
    fun `newSession is blocked when not active`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com", false)
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create parent session
                assertFailsWith<ForbiddenException> { server.sessions.newSession(userId) }
            }
        }
    }

    @Test
    fun `multiple sessions can exist for same user`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create multiple sessions
                val (session1, token1) = server.sessions.newSession(userId, label = "Session 1")
                val (session2, token2) = server.sessions.newSession(userId, label = "Session 2")
                val (session3, token3) = server.sessions.newSession(userId, label = "Session 3")

                // All should have different IDs
                assertNotEquals(session1._id, session2._id)
                assertNotEquals(session2._id, session3._id)
                assertNotEquals(session1._id, session3._id)

                // All tokens should work
                assertNotNull(server.sessions.tokenSimple.test(null, token1.string))
                assertNotNull(server.sessions.tokenSimple.test(null, token2.string))
                assertNotNull(server.sessions.tokenSimple.test(null, token3.string))
            }
        }
    }

    @Test
    fun `session secret is hashed not stored plaintext`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (session, refreshToken) = server.sessions.newSession(userId)

                // The secret in the refresh token should NOT equal the hash in the session
                assertNotEquals(refreshToken.plainTextSecret, session.secretHash)

                // The hash should be significantly different (hashing changes the value)
                assertTrue(session.secretHash.length > 10)
            }
        }
    }

    @Test
    fun `refresh token structure is valid`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (session, refreshToken) = server.sessions.newSession(userId)

                // Check refresh token can be reconstructed
                val reconstructed = RefreshToken(refreshToken.string)
                assertTrue(reconstructed.valid)
                assertEquals(refreshToken.type, reconstructed.type)
                assertEquals(refreshToken._id, reconstructed._id)
                assertEquals(refreshToken.plainTextSecret, reconstructed.plainTextSecret)
            }
        }
    }
}
