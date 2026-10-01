// by Claude
package com.lightningkite.lightningserver.sessions

import com.lightningkite.lightningserver.ForbiddenException
import com.lightningkite.lightningserver.UnauthorizedException
import com.lightningkite.lightningserver.HttpMethod
import com.lightningkite.lightningserver.auth.*
import com.lightningkite.lightningserver.definition.Runtime
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
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
import com.lightningkite.lightningserver.typed.ModelRestEndpoints
import com.lightningkite.lightningserver.typed.test
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

            context(server: ServerRuntime)
            override suspend fun fetch(id: Uuid): SessionTestUser = users[id] ?: SessionTestUser(id)
        }
    }

    class TestSessionManager(
        database: Runtime<Database>,
        private val expirationDuration: Duration? = 30.days,
        private val staleDuration: Duration? = 7.days,
    ) : SessionManager<SessionTestUser, Uuid>(
        principal = SessionTestUser,
        database = database,
        tokenFormat = Runtime { PrivateTinyTokenFormat() }
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
                                requestId = generateRequestId(),
                            )
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
    fun `tokenSimple rejects not active user`() = runBlocking {
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
                assertFailsWith<ForbiddenException>("Inactive User's refresh token should be rejected") {
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

    @Test
    fun `sub-session keeps the parent's authentication time`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")
        var time = Clock.System.now()

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}, clock = { object : Clock { override fun now() = time } }) {
                val (parent, _) = server.sessions.newSession(userId)
                time += 1.hours

                val subRefresh = server.sessions.createSubSession.test(
                    with(server.sessions) { parent.toAuth() },
                    SubSessionRequest(label = "sub")
                )
                val accessToken = server.sessions.tokenSimple.test(null, subRefresh)
                val subAuth = server.sessions.tokenFormat().read(SessionTestUser, accessToken)!!

                assertIs<AuthRequirement.Result.Rejected>(
                    AuthRequirement.Authenticated(maxAge = 10.minutes).check(subAuth)
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
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                assertEquals(createdAt, with(server.sessions) { session.toAuth() }.issuedAt)
            }
        }
    }

    @Test
    fun `terminating or deleting a session terminates sessions derived from it`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (parent, _) = server.sessions.newSession(userId)
                val childRefresh = server.sessions.createSubSession.test(
                    with(server.sessions) { parent.toAuth() },
                    SubSessionRequest(label = "child")
                )
                val child = server.sessions.sessionInfo.table().get(RefreshToken(childRefresh)._id)!!
                val grandchildRefresh = server.sessions.createSubSession.test(
                    with(server.sessions) { child.toAuth() },
                    SubSessionRequest(label = "grandchild")
                )

                server.sessions.sessionTerminate.test(with(server.sessions) { parent.toAuth() }, Unit)

                assertFailsWith<UnauthorizedException> { server.sessions.tokenSimple.test(null, childRefresh) }
                assertFailsWith<UnauthorizedException> { server.sessions.tokenSimple.test(null, grandchildRefresh) }

                val (deleted, _) = server.sessions.newSession(userId)
                val deletedChild = server.sessions.createSubSession.test(
                    with(server.sessions) { deleted.toAuth() },
                    SubSessionRequest(label = "child")
                )
                server.sessions.sessionInfo.table().deleteOneById(deleted._id)
                assertFailsWith<UnauthorizedException> { server.sessions.tokenSimple.test(null, deletedChild) }
            }
        }
    }

    @Test
    fun `a superuser terminating or deleting a session over REST terminates sessions derived from it`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        val adminId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "user@example.com")
        SessionTestUser.users[adminId] = SessionTestUser(adminId, "admin@example.com")
        val spath = Session.path(SessionTestUser.serializer(), Uuid.serializer())

        object : ServerBuilder() {
            init {
                AuthRequirement.isSuperUser = SessionTestUser.require { it.id == adminId }
            }

            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
            val rest = path.path("sessions") include ModelRestEndpoints(sessions.sessionInfo)
        }.let { server ->
            server.test({}) {
                val adminAuth = with(server.sessions) { server.sessions.newSession(adminId).first.toAuth() }

                val (terminated, _) = server.sessions.newSession(userId)
                val terminatedChild = server.sessions.createSubSession.test(
                    with(server.sessions) { terminated.toAuth() },
                    SubSessionRequest(label = "child")
                )
                server.rest.modify.test(
                    terminated._id,
                    adminAuth,
                    modification(spath) { it.terminated assign com.lightningkite.lightningserver.runtime.now() }
                )
                assertFailsWith<UnauthorizedException> { server.sessions.tokenSimple.test(null, terminatedChild) }

                val (deleted, _) = server.sessions.newSession(userId)
                val deletedChild = server.sessions.createSubSession.test(
                    with(server.sessions) { deleted.toAuth() },
                    SubSessionRequest(label = "child")
                )
                server.rest.deleteItem.test(deleted._id, adminAuth, Unit)
                assertFailsWith<UnauthorizedException> { server.sessions.tokenSimple.test(null, deletedChild) }
            }
        }
    }

    @Test
    fun `sub-session cannot be created from a terminated session`() = runBlocking {
        SessionTestUser.users.clear()
        val userId = Uuid.random()
        SessionTestUser.users[userId] = SessionTestUser(userId, "test@example.com")

        object : ServerBuilder() {
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (parent, _) = server.sessions.newSession(userId)
                val stillValidAccessToken = with(server.sessions) { parent.toAuth() }
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
            val database = setting("database", Database.Settings("ram"))

            val sessions = path.path("auth") include TestSessionManager(database = database)
        }.let { server ->
            server.test({}) {
                val (parent, _) = server.sessions.newSession(userId, scopes = setOf(GrantedScope("auth:sessions")))
                val parentAuth = with(server.sessions) { parent.toAuth() }

                val inherited = server.sessions.createSubSession.test(parentAuth, SubSessionRequest(label = "default"))
                val inheritedSession = server.sessions.sessionInfo.table().get(RefreshToken(inherited)._id)!!
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
                val narrowerSession = server.sessions.sessionInfo.table().get(RefreshToken(narrower)._id)!!
                assertEquals(setOf(GrantedScope("auth:sessions:create")), narrowerSession.scopes)
            }
        }
    }
}
