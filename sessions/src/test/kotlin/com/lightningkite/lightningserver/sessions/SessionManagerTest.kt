// by Claude
@file:OptIn(com.lightningkite.lightningserver.EngineApi::class)

package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.NotFoundException
import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.data.get
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.*
import com.lightningkite.lightningserver.plainText
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.engine
import com.lightningkite.lightningserver.runtime.handleRoot
import com.lightningkite.lightningserver.runtime.test.execute
import com.lightningkite.lightningserver.runtime.test.test
import com.lightningkite.lightningserver.serialization.registerBasicMediaTypeCoders
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.lightningkite.lightningserver.runtime.Execution
import org.slf4j.LoggerFactory
import com.lightningkite.lightningserver.sessions.token.PrivateTinyTokenFormat
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.test
import com.lightningkite.lightningserver.typed.AuthAccess
import com.lightningkite.services.database.optics.*
import com.lightningkite.services.database.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
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
            var fetchCount = 0

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): SessionTestUser {
                fetchCount++
                return users[id] ?: SessionTestUser(id)
            }
        }
    }

    class TestSessionManager(
        database: Runtime<Database>,
        private val expirationDuration: Duration? = 30.days,
        private val staleDuration: Duration? = 7.days,
        private val allowedScopes: (SessionTestUser) -> Set<GrantedScope> = { setOf(GrantedScope.root) },
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

        context(server: ServerRuntime)
        override suspend fun permitScopes(subject: SessionTestUser): Set<GrantedScope> = allowedScopes(subject)
    }

    @Test
    fun `newSession creates a session and returns refresh token`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create a new session
                val (session, refreshToken) = execute { server.sessions.newSession(userId) }

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

            init {
                registerBasicMediaTypeCoders()
                install(AccessLogInterceptor())
            }

            val sessions = path.path("auth") include TestSessionManager(database = database)
            val ping = path.path("ping").get bind HttpHandler<PathSpec0> { HttpResponse.plainText("pong") }
        }.let { server ->
            server.test({}) {
                val (_, refreshToken) = execute { server.sessions.newSession(userId) }
                val accessToken = server.sessions.tokenSimple.test(null, refreshToken.string)

                // Attach the log capture after settings are applied, so the framework's logback setup can't
                // wipe it. Scoped to this logger and detached in finally.
                val logbackLogger = LoggerFactory.getLogger("com.lightningkite.lightningserver") as Logger
                val appender = ListAppender<ILoggingEvent>().apply { start() }
                logbackLogger.level = Level.INFO
                logbackLogger.addAppender(appender)
                try {
                    runBlocking {
                        engine.handleRoot(
                            HttpRequest(
                                path = RawHttpEndpoint(asString = "/ping", method = HttpMethod.GET),
                                queryParameters = QueryParameters.EMPTY,
                                headers = HttpHeaders { add(HttpHeader.Authorization, "Bearer $accessToken") },
                                domain = "example.com",
                                protocol = "https",
                                sourceIp = "local",
                            ),
                            Execution.ID.generate(),
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
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create a new session
                val (session, refreshToken) = execute { server.sessions.newSession(userId) }

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
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (session, refreshToken) = execute { server.sessions.newSession(userId) }
                // The harness supplies a source ip but no User-Agent header.
                server.sessions.tokenSimple.test(null, refreshToken.string)

                val stored = execute { server.sessions.sessionInfo.table().get(session._id) }
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
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (session, refreshToken) = execute { server.sessions.newSession(userId) }

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
                val resolved = execute { request[Authentication.CacheKey] }
                assertNotNull(resolved, "the refresh token was not accepted, so no session use was recorded")

                val stored = execute { server.sessions.sessionInfo.table().get(session._id) }
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
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(
                database = database,
                sessionSignals = { table -> table.postCreate { created += it._id } },
            )
        }.let { server ->
            server.test({}) {
                val (session, _) = execute { server.sessions.newSession(userId) }
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
                registerBasicMediaTypeCoders()
                // Make the test subject a super user, so `isRoot` resolves to Always. Without this
                // every branch collapses to Never for an unprivileged caller and the assertion below
                // would hold no matter what delete was granted.
                AuthRequirement.isSuperUser = SessionTestUser.require()
            }
        }.let { server ->
            server.test({}) {
                val permissions = execute {
                    val (session, _) = server.sessions.newSession(userId)
                    val auth = with(server.sessions) { session.toAuth() }
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
            init { registerBasicMediaTypeCoders() }

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
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (_, refreshToken) = execute { server.sessions.newSession(userId) }

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
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create a new session
                val (session, _) = execute { server.sessions.newSession(userId) }

                // Convert to authentication
                val auth = execute { with(server.sessions) { session.toAuth() } }

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
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val limitedScopes = setOf(GrantedScope("api:read"))

                // Create a session with limited scopes
                val (session, _) = execute {
                    server.sessions.newSession(
                        subjectId = userId,
                        scopes = limitedScopes
                    )
                }

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
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (session, _) = execute {
                    server.sessions.newSession(
                        subjectId = userId,
                        label = "Mobile App"
                    )
                }

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
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create parent session
                val (parentSession, _) = execute { server.sessions.newSession(userId) }

                // Create derived session
                val (childSession, _) = execute {
                    server.sessions.newSession(
                        subjectId = userId,
                        derivedFrom = parentSession._id
                    )
                }

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
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create parent session
                assertFailsWith<ForbiddenException> { execute { server.sessions.newSession(userId) } }
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
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                // Create multiple sessions
                val (session1, token1) = execute { server.sessions.newSession(userId, label = "Session 1") }
                val (session2, token2) = execute { server.sessions.newSession(userId, label = "Session 2") }
                val (session3, token3) = execute { server.sessions.newSession(userId, label = "Session 3") }

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
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (session, refreshToken) = execute { server.sessions.newSession(userId) }

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
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (session, refreshToken) = execute { server.sessions.newSession(userId) }

                // Check refresh token can be reconstructed
                val reconstructed = RefreshToken(refreshToken.string)
                assertTrue(reconstructed.valid)
                assertEquals(refreshToken.type, reconstructed.type)
                assertEquals(refreshToken._id, reconstructed._id)
                assertEquals(refreshToken.plainTextSecret, reconstructed.plainTextSecret)
            }
        }
    }

    @Test
    fun `sub-session keeps the parent's authentication time`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")
        var time = Clock.System.now()

        object : ServerBuilder() {
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}, clock = { object : Clock { override fun now() = time } }) {
                val (parent, _) = execute { server.sessions.newSession(userId) }
                time += 1.hours

                val subRefresh = server.sessions.createSubSession.test(
                    execute { with(server.sessions) { parent.toAuth() } },
                    SubSessionRequest(label = "sub")
                )
                val accessToken = server.sessions.tokenSimple.test(null, subRefresh)
                val subAuth = execute { server.sessions.tokenFormat().read(SessionTestUser, accessToken) }!!

                assertIs<AuthRequirement.Result.Rejected>(
                    execute { AuthRequirement.Authenticated(maxAge = 10.minutes).check(subAuth) }
                )
            }
        }
    }

    @Test
    fun `legacy stored session without authenticatedAt is treated as authenticated at createdAt`() = runBlocking {
        val createdAt = Instant.parse("2024-01-01T00:00:00Z")
        val json = """{"secretHash":"x","subjectId":"${Uuid.random()}","createdAt":"$createdAt","lastUsed":"$createdAt"}"""
        val session = Json.decodeFromString(
            Session.serializer(SessionTestUser.serializer(), Uuid.serializer()),
            json
        )

        object : ServerBuilder() {
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                assertEquals(createdAt, execute { with(server.sessions) { session.toAuth() } }.issuedAt)
            }
        }
    }

    @Test
    fun `terminating or deleting a session terminates sessions derived from it`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")

        object : ServerBuilder() {
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (parent, _) = execute { server.sessions.newSession(userId) }
                val childRefresh = server.sessions.createSubSession.test(
                    execute { with(server.sessions) { parent.toAuth() } },
                    SubSessionRequest(label = "child")
                )
                val child = execute { server.sessions.sessionInfo.table().get(RefreshToken(childRefresh)._id) }!!
                val grandchildRefresh = server.sessions.createSubSession.test(
                    execute { with(server.sessions) { child.toAuth() } },
                    SubSessionRequest(label = "grandchild")
                )

                server.sessions.sessionTerminate.test(execute { with(server.sessions) { parent.toAuth() } }, Unit)

                assertFailsWith<UnauthorizedException> { server.sessions.tokenSimple.test(null, childRefresh) }
                assertFailsWith<UnauthorizedException> { server.sessions.tokenSimple.test(null, grandchildRefresh) }

                val (deleted, _) = execute { server.sessions.newSession(userId) }
                val deletedChild = server.sessions.createSubSession.test(
                    execute { with(server.sessions) { deleted.toAuth() } },
                    SubSessionRequest(label = "child")
                )
                execute { server.sessions.sessionInfo.table().deleteOneById(deleted._id) }
                assertFailsWith<UnauthorizedException> { server.sessions.tokenSimple.test(null, deletedChild) }
            }
        }
    }

    @Test
    fun `a superuser terminating a session over REST terminates sessions derived from it`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val adminId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "user@example.com")
        SessionTestUser.users[adminId] = SessionTestUser(adminId, "admin@example.com")
        val spath = IdentityLens(Session.serializer(SessionTestUser.serializer(), Uuid.serializer()))

        object : ServerBuilder() {
            init {
                registerBasicMediaTypeCoders()
                AuthRequirement.isSuperUser = SessionTestUser.require { it.id == adminId }
            }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
            val rest = path.path("sessions") include ModelRestEndpoints(sessions.sessionInfo)
        }.let { server ->
            server.test({}) {
                val adminAuth = execute { with(server.sessions) { server.sessions.newSession(adminId).first.toAuth() } }

                val (terminated, _) = execute { server.sessions.newSession(userId) }
                val terminatedChild = server.sessions.createSubSession.test(
                    execute { with(server.sessions) { terminated.toAuth() } },
                    SubSessionRequest(label = "child")
                )
                server.rest.modify.test(
                    terminated._id,
                    adminAuth,
                    Modification(spath) { it.terminated assign com.lightningkite.lightningserver.runtime.now() }
                )
                assertFailsWith<UnauthorizedException> { server.sessions.tokenSimple.test(null, terminatedChild) }

                // Sessions are never erased over REST, not even by a superuser; they are the audit record.
                val (kept, _) = execute { server.sessions.newSession(userId) }
                assertFailsWith<NotFoundException> { server.rest.deleteItem.test(kept._id, adminAuth, Unit) }
            }
        }
    }

    @Test
    fun `sub-session cannot be created from a terminated session`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")

        object : ServerBuilder() {
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (parent, _) = execute { server.sessions.newSession(userId) }
                val stillValidAccessToken = execute { with(server.sessions) { parent.toAuth() } }
                server.sessions.sessionTerminate.test(stillValidAccessToken, Unit)

                assertFailsWith<UnauthorizedException> {
                    server.sessions.createSubSession.test(stillValidAccessToken, SubSessionRequest(label = "sub"))
                }
            }
        }
    }

    @Test
    fun `sub-session scopes default to the parent's and cannot be broader`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")

        object : ServerBuilder() {
            init { registerBasicMediaTypeCoders() }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (parent, _) = execute { server.sessions.newSession(userId, scopes = setOf(GrantedScope("auth:sessions"))) }
                val parentAuth = execute { with(server.sessions) { parent.toAuth() } }

                val inherited = server.sessions.createSubSession.test(parentAuth, SubSessionRequest(label = "default"))
                val inheritedSession = execute { server.sessions.sessionInfo.table().get(RefreshToken(inherited)._id) }!!
                assertEquals(setOf(GrantedScope("auth:sessions")), inheritedSession.scopes)

                assertFailsWith<ForbiddenException> {
                    server.sessions.createSubSession.test(
                        parentAuth,
                        SubSessionRequest(label = "other", scopes = setOf(GrantedScope("auth:self")))
                    )
                }
                val narrower = server.sessions.createSubSession.test(
                    parentAuth,
                    SubSessionRequest(label = "narrower", scopes = setOf(GrantedScope("auth:sessions:create")))
                )
                val narrowerSession = execute { server.sessions.sessionInfo.table().get(RefreshToken(narrower)._id) }!!
                assertEquals(setOf(GrantedScope("auth:sessions:create")), narrowerSession.scopes)
            }
        }
    }

    @Test
    fun `permitScopes limits access tokens and lifts without re-authenticating`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")
        val setupOnly = setOf(GrantedScope("auth:proofs:totp"))
        var mfaEstablished = false

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(
                database = database,
                allowedScopes = { if (mfaEstablished) setOf(GrantedScope.root) else setupOnly },
            )
        }.let { server ->
            server.test({}) {
                val (session, refreshToken) = execute { server.sessions.newSession(subjectId = userId) }
                // The session keeps what was requested; the limit is applied on use.
                assertEquals(setOf(GrantedScope.root), session.scopes)

                val restricted = server.sessions.tokenSimple.test(null, refreshToken.string)
                assertEquals(setupOnly, execute { server.sessions.tokenFormat().read(SessionTestUser, restricted)!!.scopes })

                mfaEstablished = true
                val full = server.sessions.tokenSimple.test(null, refreshToken.string)
                assertEquals(setOf(GrantedScope.root), execute { server.sessions.tokenFormat().read(SessionTestUser, full)!!.scopes })
            }
        }
    }

    @Test
    fun `permitScopes also limits a refresh token used as a bearer token`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")
        val setupOnly = setOf(GrantedScope("auth:proofs:totp"))

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(
                database = database,
                allowedScopes = { setupOnly },
            )
        }.let { server ->
            server.test({}) {
                val (_, refreshToken) = execute { server.sessions.newSession(userId) }
                val auth = execute { server.sessions.read(
                    HttpRequest<PathSpec>(
                        path = RawHttpEndpoint(asString = "/ping", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders { add(HttpHeader.Authorization, "Bearer ${refreshToken.string}") },
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                ) }
                assertEquals(setupOnly, auth!!.scopes)
            }
        }
    }

    @Test
    fun `permitScopes intersects hierarchically with session scopes`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(
                database = database,
                allowedScopes = { setOf(GrantedScope("api"), GrantedScope("admin:read")) },
            )
        }.let { server ->
            server.test({}) {
                suspend fun effective(vararg stored: String) = with(server.sessions) {
                    execute { newSession(userId, scopes = stored.mapTo(HashSet()) { GrantedScope(it) }).first.toAuth().scopes }
                }

                // Narrower session scope within an allowed one is kept
                assertEquals(setOf(GrantedScope("api:read")), effective("api:read"))
                // Broader session scope is narrowed to what's allowed
                assertEquals(setOf(GrantedScope("admin:read")), effective("admin"))
                // Root session scope becomes exactly the allowed set
                assertEquals(setOf(GrantedScope("api"), GrantedScope("admin:read")), effective("*"))
                // Unrelated scopes are dropped
                assertEquals(emptySet(), effective("other"))
            }
        }
    }

    @Test
    fun `refreshing loads the subject once and caches it on the authentication`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (_, refreshToken) = execute { server.sessions.newSession(userId) }

                SessionTestUser.fetchCount = 0
                server.sessions.tokenSimple.test(null, refreshToken.string)
                assertEquals(1, SessionTestUser.fetchCount)

                // Refresh token used as a bearer token: the handler's fetch() hits the cache
                SessionTestUser.fetchCount = 0
                val auth = execute { server.sessions.read(
                    HttpRequest<PathSpec>(
                        path = RawHttpEndpoint(asString = "/ping", method = HttpMethod.GET),
                        queryParameters = QueryParameters.EMPTY,
                        headers = HttpHeaders { add(HttpHeader.Authorization, "Bearer ${refreshToken.string}") },
                        domain = "example.com",
                        protocol = "https",
                        sourceIp = "local",
                    )
                )!! }
                assertEquals(userId, execute { auth.fetch()._id })
                assertEquals(1, SessionTestUser.fetchCount)
            }
        }
    }

    @Test
    fun `a refresh token with the wrong secret does not load the subject`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (_, refreshToken) = execute { server.sessions.newSession(userId) }
                val forged = RefreshToken(refreshToken.type, refreshToken._id, "not-the-secret")

                SessionTestUser.fetchCount = 0
                assertFailsWith<UnauthorizedException> { server.sessions.tokenSimple.test(null, forged.string) }
                assertEquals(0, SessionTestUser.fetchCount)
            }
        }
    }

    @Test
    fun `terminating another subject's session is forbidden and leaves it active`() = runBlocking {
        SessionTestUser.users.clear()
        val ownerId = Uuid.random()
        val otherId = Uuid.random()
        SessionTestUser.users[ownerId] = SessionTestUser(ownerId, "owner@example.com")
        SessionTestUser.users[otherId] = SessionTestUser(otherId, "other@example.com")

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (target, targetRefresh) = execute { server.sessions.newSession(ownerId) }
                val (otherSession, _) = execute { server.sessions.newSession(otherId) }
                val otherAuth = execute { with(server.sessions) { otherSession.toAuth() } }

                assertFailsWith<ForbiddenException> {
                    server.sessions.otherSessionTerminate.test(target._id, otherAuth, Unit)
                }
                assertFailsWith<ForbiddenException> {
                    server.sessions.otherSessionTerminate.test(Uuid.random(), otherAuth, Unit)
                }
                assertNull(execute { server.sessions.sessionInfo.table().get(target._id)!!.terminated })
                server.sessions.tokenSimple.test(null, targetRefresh.string)

                val ownerAuth =execute {  with(server.sessions) { server.sessions.newSession(ownerId).first.toAuth() } }
                server.sessions.otherSessionTerminate.test(target._id, ownerAuth, Unit)
                assertNotNull(execute { server.sessions.sessionInfo.table().get(target._id)!!.terminated })
            }
        }
    }

    @Test
    fun `creating a session from a loaded subject does not reload it`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val user = SessionTestUser(userId, "test@example.com")
        SessionTestUser.users[userId] = user

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                SessionTestUser.fetchCount = 0
                val (parent, _) = execute { server.sessions.newSession(user) }
                assertEquals(userId, parent.subjectId)
                assertEquals(0, SessionTestUser.fetchCount)

                // The parent auth already carries the subject, so the sub-session reuses it
                val parentAuth = execute { with(server.sessions) { parent.toAuth(user) } }
                server.sessions.createSubSession.test(parentAuth, SubSessionRequest(label = "child"))
                assertEquals(0, SessionTestUser.fetchCount)

                SessionTestUser.users[userId] = user.copy(active = false)
                assertFailsWith<ForbiddenException> { execute { server.sessions.newSession(user.copy(active = false)) }}
            }
        }
    }
}
